package com.github.jonnylaw.examples

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import java.io.{File, PrintWriter}
import akka.stream.scaladsl._
import akka.util.ByteString

import com.github.jonnylaw.model._
import com.github.jonnylaw.model.Streaming._
import com.github.jonnylaw.model.POMP._
import com.github.jonnylaw.model.StateSpace._
import com.github.jonnylaw.model.DataTypes._
import com.github.jonnylaw.model.{State, Model}
import com.github.jonnylaw.model.SimData._

import com.github.jonnylaw.model.State._
import com.github.jonnylaw.model.Parameters._
import scala.concurrent.{duration, Await}
import scala.concurrent.duration._
import com.github.jonnylaw.model.Utilities._
import breeze.stats.distributions.MarkovChain._
import breeze.stats.{mean, variance}
import breeze.linalg.{DenseVector, DenseMatrix, diag}
import cats.implicits._


object MultipleObservations {
  def main(args: Array[String]): Unit = {
    import scala.concurrent.ExecutionContext.Implicits.global
    implicit val system = ActorSystem("StreamingPMMH")
    implicit val materializer = ActorMaterializer()

    // define a model for count data, where the rate of the poisson process changes
    // periodically on a weekly and daily basis
    val poissonParam: Parameters = LeafParameter(
      GaussianParameter(0.0, 1.0),
      None,
      BrownianParameter(0.0, 1.0))
    val seasonalParamDaily: Parameters = LeafParameter(
      GaussianParameter(DenseVector(Array.fill(6)(0.0)),
      diag(DenseVector(Array.fill(6)(1.0)))),
      None,
      BrownianParameter(DenseVector(Array.fill(6)(0.1)), diag(DenseVector(Array.fill(6)(0.4)))))
    val seasonalParamWeekly: Parameters = LeafParameter(
      GaussianParameter(DenseVector(Array.fill(6)(0.0)),
      diag(DenseVector(Array.fill(6)(1.0)))),
      None,
      BrownianParameter(DenseVector(Array.fill(6)(0.1)), diag(DenseVector(Array.fill(6)(0.4)))))

    // compose the parameters and models together
    // note the models must be composed in the same order as the parameters
    val p = poissonParam |+| seasonalParamDaily |+| seasonalParamWeekly
    val poisson = PoissonModel(stepBrownian)
    val daily = SeasonalModel(24, 3, stepBrownian)
    val weekly = SeasonalModel(24*7, 3, stepBrownian)
    val poissonMod = poisson |+| daily |+| weekly

    // record observations at every other integer time point from 1 to 100
    val times = (1 to 100 by 2).map(_.toDouble).toList

    // create a new data type for count data recorded from multiple sensors
    case class CountData(sensorId: Int, time: Time, count: Int)

    // simulate three different realisations of the count data corresponding to three sensors
    val simulatedCounts = (1 to 3).map(id => (id, simData(times, poissonMod(p)))).
     flatMap { case (id, data) => data map (d => CountData(id, d.t, d.observation.toInt)) }

    // since the sensors are independent, we can parallelise over them
   Source(simulatedCounts).
     groupBy(3, _.sensorId).
     fold(Vector[CountData]()) {
       case (acc: Vector[CountData], vc) => acc :+ vc
     }.
     mapAsync(parallelism = 3) { vc: Vector[CountData] =>

       println(s"Processing stationId: ${vc.head.sensorId}")
       // transform each set of observations into a Vector of Data objects, for use in the particle filter
       val data = vc map (x => Data(x.time, x.count, None, None, None))

       // define the particle filter using 200 particles and the same poisson model we generated the data from
       val filter = Filter(poissonMod, ParticleFilter.multinomialResampling)
       val mll = filter.llFilter(data.sortBy(_.t), data.map(_.t).min)(200) _

       // PMMH is a random Akka stream, this
       // means we can write asynchronously to a file
       // without holding all iterations in memory
       ParticleMetropolis(mll, p, Parameters.perturb(0.1)).itersAkka.
         map(x => x.params).
         take(10000).
         map(a => ByteString(a + "\n")).
         runWith(FileIO.toFile(new File(s"poissonSimulate-station${vc.head.sensorId}.csv")))
     }.
     mergeSubstreams.
     runWith(Sink.onComplete { _ =>
         system.shutdown()
     })
   }
}
