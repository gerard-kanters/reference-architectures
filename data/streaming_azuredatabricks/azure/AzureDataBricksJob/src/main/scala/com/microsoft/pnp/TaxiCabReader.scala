package com.microsoft.pnp

import java.sql.Timestamp

import com.datastax.spark.connector.cql.CassandraConnector
import com.microsoft.pnp.spark.StreamingMetricsListener
import org.apache.spark.eventhubs.{EventHubsConf, EventPosition}
import org.apache.spark.metrics.source.{AppAccumulators, AppMetrics}
import org.apache.spark.sql.catalyst.expressions.{CsvToStructs, Expression}
import org.apache.spark.sql.functions._
import org.apache.spark.sql.streaming.{GroupState, OutputMode}
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{Column, SparkSession}
import org.apache.spark.{SparkConf, SparkEnv}

case class InputRow(
                     medallion: Long,
                     hackLicense: Long,
                     vendorId: String,
                     pickupTime: Timestamp,
                     rateCode: Int,
                     storeAndForwardFlag: String,
                     dropoffTime: Timestamp,
                     passengerCount: Int,
                     tripTimeInSeconds: Double,
                     tripDistanceInMiles: Double,
                     pickupLon: Double,
                     pickupLat: Double,
                     dropoffLon: Double,
                     dropoffLat: Double,
                     paymentType: String,
                     fareAmount: Double,
                     surcharge: Double,
                     mtaTax: Double,
                     tipAmount: Double,
                     tollsAmount: Double,
                     totalAmount: Double,
                     pickupNeighborhood: String,
                     dropoffNeighborhood: String) extends Serializable

case class NeighborhoodState(neighborhoodName: String, var avgFarePerRide: Double, var ridesCount: Double) extends Serializable

object TaxiCabReader {
  private def withExpr(expr: Expression): Column = new Column(expr)

  def main(args: Array[String]) {
    val conf = new JobConfiguration(args)
    val rideEventHubConnectionString = getSecret(
      conf.secretScope(), conf.taxiRideEventHubSecretName())
    val fareEventHubConnectionString = getSecret(
      conf.secretScope(), conf.taxiFareEventHubSecretName())

    // getting cassandra secrets
    val cassandraEndPoint = getSecret(
      conf.secretScope(), conf.cassandraConnectionHostSecretName())
    val cassandraUserName = getSecret(
      conf.secretScope(), conf.cassandraUserSecretName())
    val cassandraPassword = getSecret(
      conf.secretScope(), conf.cassandraPasswordSecretName())

    val spark = SparkSession.builder().config("spark.master", "local[10]").getOrCreate()
    import spark.implicits._

    // Databricks spark session is created upfront . it is not possible to
    // update the conf later . hence this conf is just created with values from
    // secrets just for initiating the cassandra driver
    // please note :- when spark submit is used, spark session is created in the main method
    // what ever values that gets provided in the main while initiating spark should be able available by accessing
    // sparksession.getconf
    val sparkConfForCassandraDriver = new SparkConf()
      .set("spark.cassandra.connection.host", cassandraEndPoint)
      .set("spark.cassandra.connection.port", "10350")
      .set("spark.cassandra.connection.ssl.enabled", "true")
      .set("spark.cassandra.auth.username", cassandraUserName)
      .set("spark.cassandra.auth.password", cassandraPassword)
      .set("spark.master", "local[10]")
      .set("spark.cassandra.output.batch.size.rows", "1")
      .set("spark.cassandra.connection.connections_per_executor_max", "2")
      .set("spark.cassandra.output.concurrent.writes", "5")
      .set("spark.cassandra.output.batch.grouping.buffer.size", "300")
      .set("spark.cassandra.connection.keep_alive_ms", "5000")

    // Initializing the connector in the driver . connector is serializable
    // will be sending it to foreach sink that gets executed in the workers.
    val connector = CassandraConnector(sparkConfForCassandraDriver)

    @transient val appMetrics = new AppMetrics(spark.sparkContext)
    appMetrics.registerGauge("metricsregistrytest.processed",
      AppAccumulators.getProcessedInputCountInstance(spark.sparkContext))
    SparkEnv.get.metricsSystem.registerSource(appMetrics)

    @transient lazy val NeighborhoodFinder = GeoFinder.createGeoFinder(
      conf.neighborhoodFileURL())

    val neighborhoodFinder = (lon: Double, lat: Double) => {
      NeighborhoodFinder.getNeighborhood(lon, lat).get()
    }
    val to_neighborhood = spark.udf.register("neighborhoodFinder", neighborhoodFinder)

    def from_csv(e: Column, schema: StructType, options: Map[String, String]): Column = withExpr {
      CsvToStructs(schema, options, e.expr)
    }

    spark.streams.addListener(new StreamingMetricsListener())

    val rideEventHubOptions = EventHubsConf(rideEventHubConnectionString)
      .setConsumerGroup(conf.taxiRideConsumerGroup())
      .setStartingPosition(EventPosition.fromStartOfStream)
    val rideEvents = spark.readStream
      .format("eventhubs")
      .options(rideEventHubOptions.toMap)
      .load

    val fareEventHubOptions = EventHubsConf(fareEventHubConnectionString)
      .setConsumerGroup(conf.taxiFareConsumerGroup())
      .setStartingPosition(EventPosition.fromStartOfStream)
    val fareEvents = spark.readStream
      .format("eventhubs")
      .options(fareEventHubOptions.toMap)
      .load

    val transformedRides = rideEvents
      .select(
        $"body"
          .cast(StringType)
          .as("messageData"),
        from_json($"body".cast(StringType), RideSchema)
          .as("ride"))
      .transform(ds => {
        ds.withColumn(
          "errorMessage",
          when($"ride".isNull,
            lit("Error decoding JSON"))
            .otherwise(lit(null))
        )
      })

    val rides = transformedRides
      .filter($"errorMessage".isNull)
      .select(
        $"ride.*",
        to_neighborhood($"ride.pickupLon", $"ride.pickupLat")
          .as("pickupNeighborhood"),
        to_neighborhood($"ride.dropoffLon", $"ride.dropoffLat")
          .as("dropoffNeighborhood")
      )
      .withWatermark("pickupTime", conf.taxiRideWatermarkInterval())

    val csvOptions = Map("header" -> "true", "multiLine" -> "true")
    val transformedFares = fareEvents
      .select(
        $"body"
          .cast(StringType)
          .as("messageData"),
        from_csv($"body".cast(StringType), FareSchema, csvOptions)
          .as("fare"))
      .transform(ds => {
        ds.withColumn(
          "errorMessage",
          when($"fare".isNull,
            lit("Error decoding CSV"))
            .when(to_timestamp($"fare.pickupTimeString", "yyyy-MM-dd HH:mm:ss").isNull,
              lit("Error parsing pickupTime"))
            .otherwise(lit(null))
        )
      })
      .transform(ds => {
        ds.withColumn(
          "pickupTime",
          when($"fare".isNull,
            lit(null))
            .otherwise(to_timestamp($"fare.pickupTimeString", "yyyy-MM-dd HH:mm:ss"))
        )
      })

    val fares = transformedFares
      .filter($"errorMessage".isNull)
      .select(
        $"fare.*",
        $"pickupTime"
      )
      .withWatermark("pickupTime", conf.taxiFareWatermarkInterval())

    val mergedTaxiTrip = rides.join(fares, Seq("medallion", "hackLicense", "vendorId", "pickupTime"))

    val maxAvgFarePerNeighborhood = mergedTaxiTrip.selectExpr("medallion", "hackLicense", "vendorId", "pickupTime", "rateCode", "storeAndForwardFlag", "dropoffTime", "passengerCount", "tripTimeInSeconds", "tripDistanceInMiles", "pickupLon", "pickupLat", "dropoffLon", "dropoffLat", "paymentType", "fareAmount", "surcharge", "mtaTax", "tipAmount", "tollsAmount", "totalAmount", "pickupNeighborhood", "dropoffNeighborhood")
      .as[InputRow]
      .groupBy(window($"pickupTime", conf.windowInterval()), $"pickupNeighborhood")
      .agg(
        count("*").as("rideCount"),
        sum($"fareAmount").as("totalFareAmount"),
        sum($"tipAmount").as("totalTipAmount")
      )
      .select($"window.start", $"window.end", $"pickupNeighborhood", $"rideCount", $"totalFareAmount", $"totalTipAmount")

    maxAvgFarePerNeighborhood
      .writeStream
      .queryName("maxAvgFarePerNeighborhood_cassandra_insert")
      .outputMode(OutputMode.Append())
      .foreach(new CassandraSinkForeach(connector))
      .start()
      .awaitTermination()
  }

  def updateNeighborhoodStateWithEvent(state: NeighborhoodState, input: InputRow): NeighborhoodState = {
    state.avgFarePerRide = ((state.avgFarePerRide * state.ridesCount) + input.fareAmount) / (state.ridesCount + 1)
    state.ridesCount += 1
    state
  }

  def updateForEvents(neighborhoodName: String,
                      inputs: Iterator[InputRow],
                      oldState: GroupState[NeighborhoodState]): Iterator[NeighborhoodState] = {

    var state: NeighborhoodState = if (oldState.exists) oldState.get else NeighborhoodState(neighborhoodName, 0, 0)

    for (input <- inputs) {
      state = updateNeighborhoodStateWithEvent(state, input)
      oldState.update(state)
    }

    Iterator(state)
  }
}