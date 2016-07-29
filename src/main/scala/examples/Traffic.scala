package examples

import model.POMP._

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import java.io.{File, PrintWriter}
import akka.stream.scaladsl._
import scala.concurrent.{duration, Await}
import scala.concurrent.duration._
import akka.util.ByteString
import GraphDSL.Implicits._
import akka.stream.ClosedShape
import java.nio.file.{Path, Paths}

import model._
import model.Streaming._
import model.POMP.{PoissonModel, SeasonalModel, LogGaussianCox}
import model.DataTypes._
import model.{State, Model}
import model.SimData._
import model.Utilities._
import model.ParticleFilter._
import model.State._
import model.Parameters._
import model.StateSpace._
import java.io.{PrintWriter, File}
import breeze.stats.distributions.Gaussian
import breeze.linalg.{DenseVector, diag}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import breeze.numerics.exp
import cats.implicits._

object PoissonCars {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("FitCars")
    implicit val materializer = ActorMaterializer()

    val data = scala.io.Source.fromFile("poissonCars.csv").getLines.toVector.
      drop(1).
      map(a => a.split(",")).
      map(rs => Data(rs(5).toDouble, rs(2).toDouble, None, None, None))

    // define the model
    val poissonParam: Parameters = LeafParameter(
      GaussianParameter(6.0, 0.1),
      None,
      OrnsteinParameter(6.0, 0.1, 0.1))
    val seasonalParamDaily = LeafParameter(
      GaussianParameter(DenseVector.fill(6)(-0.5), diag(DenseVector.fill(6)(0.1))),
      None,
      OrnsteinParameter(
        theta = DenseVector.fill(6)(-1.0),
        alpha = DenseVector.fill(6)(0.1),
        sigma = DenseVector.fill(6)(0.2)))

    val initParams = poissonParam |+| seasonalParamDaily

    val poisson = PoissonModel(stepOrnstein)
    val daily = SeasonalModel(24, 3, stepOrnstein)
    val unparamMod = poisson |+| daily

    val (iters, particles, delta) = (args.head.toInt, args(1).toInt, args(2).toDouble)

    val filter = Filter(unparamMod, ParticleFilter.multinomialResampling, data.map(_.t).min)
    val mll = filter.llFilter(data)(particles) _
    val mh = ParticleMetropolis(mll, initParams, Parameters.perturb(0.05))

    val out = FileIO.toPath(Paths.get("./PoissonTrafficMCMC.csv"))

    val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val bcast = builder.add(Broadcast[MetropState](2))
      
      mh.itersAkka ~> bcast
      bcast ~> monitorStream(1000, 1) ~> Sink.ignore

      bcast ~> Flow[MetropState].take(10000) ~> Flow[MetropState].map(p => ByteString(s"$p\n")) ~> out

      ClosedShape
    })

    graph.run()
  }
}

object LgcpCars {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("FitCarsLgcp")
    implicit val materializer = ActorMaterializer()

    val data = scala.io.Source.fromFile("lgcpCars.csv").getLines.toVector.
      drop(1).
      map(a => a.split(",")).
      map(rs => Data(rs(1).toDouble, true, None, None, None))

    // define the model
    val lgcpParam: Parameters = LeafParameter(
      GaussianParameter(6.0, 0.1),
      None,
      OrnsteinParameter(6.0, 0.1, 0.1))

    val seasonalParamDaily = LeafParameter(
      GaussianParameter(DenseVector.fill(6)(-0.5), diag(DenseVector.fill(6)(0.1))),
      None,
      OrnsteinParameter(
        theta = DenseVector.fill(6)(-1.0),
        alpha = DenseVector.fill(6)(0.1),
        sigma = DenseVector.fill(6)(0.2)))

    val initParams = lgcpParam |+| seasonalParamDaily

    val poisson = LogGaussianCox(stepOrnstein)
    val daily = SeasonalModel(24, 3, stepOrnstein)
    val unparamMod = poisson |+| daily

    val (iters, particles, delta) = (args.head.toInt, args(1).toInt, args(2).toDouble)

    val filter = FilterLgcp(unparamMod, ParticleFilter.multinomialResampling, 0, data.map(_.t).min)
    val mll = filter.llFilter(data)(particles) _
    val mh = ParticleMetropolis(mll, initParams, Parameters.perturb(0.05))

    val out = FileIO.toPath(Paths.get("./LgcpCarsMCMC.csv"))

    val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val bcast = builder.add(Broadcast[MetropState](2))
      
      mh.itersAkka ~> bcast
      bcast ~> monitorStream(1000, 1) ~> Sink.ignore

      bcast ~> Flow[MetropState].take(10000) ~> Flow[MetropState].map(p => ByteString(s"$p\n")) ~> out

      ClosedShape
    })

    graph.run()
  }
}

object NegBinCars {
  def main(args: Array[String]): Unit = {
    implicit val system = ActorSystem("FitCars")
    implicit val materializer = ActorMaterializer()

    val data = scala.io.Source.fromFile("poissonCars.csv").getLines.toVector.
      drop(1).
      map(a => a.split(",")).
      map(rs => Data(rs(5).toDouble, rs(2).toDouble, None, None, None))

    // define the model
    val poissonParam: Parameters = LeafParameter(
      GaussianParameter(6.0, 0.1),
      Some(1.0),
      OrnsteinParameter(6.0, 0.1, 0.1))
    val seasonalParamDaily: Parameters = LeafParameter(
      GaussianParameter(DenseVector(-0.5, -0.3, -0.75, -0.3, -0.3, -0.5), diag(DenseVector.fill(6)(0.1))),
      None,
      OrnsteinParameter(
        theta = DenseVector(-1.2, -1.0, -1.0, -0.5, -0.5, -0.7),
        alpha = DenseVector.fill(6)(0.1),
        sigma = DenseVector.fill(6)(0.2)))
    val seasonalParamWeekly: Parameters = LeafParameter(
      GaussianParameter(DenseVector.fill(6)(0.01), diag(DenseVector.fill(6)(0.1))),
      None,
      OrnsteinParameter(
        theta = DenseVector.fill(6)(-1.0),
        alpha = DenseVector.fill(6)(0.1),
        sigma = DenseVector.fill(6)(0.2)))

    val initParams = poissonParam |+| seasonalParamDaily |+| seasonalParamWeekly

    val negBin = negativeBinomial(stepOrnstein)
    val daily = SeasonalModel(24, 3, stepOrnstein)
    val weekly = SeasonalModel(24*7, 3, stepOrnstein)
    val unparamMod = negBin |+| daily |+| weekly

    val (iters, particles, delta) = (args.head.toInt, args(1).toInt, args(2).toDouble)
    
    val filter = Filter(unparamMod, ParticleFilter.multinomialResampling, data.map(_.t).min)
    val mll = filter.llFilter(data)(particles) _
    val mh = ParticleMetropolis(mll, initParams, Parameters.perturb(0.05))

    val out = FileIO.toPath(Paths.get("./NegBinCars.csv"))

    val graph = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder =>
      val bcast = builder.add(Broadcast[MetropState](2))
      
      mh.itersAkka ~> bcast
      bcast ~> monitorStream(1000, 1) ~> Sink.ignore

      bcast ~> Flow[MetropState].take(10000) ~> Flow[MetropState].map(p => ByteString(s"$p\n")) ~> out

      ClosedShape
    })

    graph.run()

  }
}

object SimCars extends App {
  val poissonParam: Parameters = LeafParameter(
    GaussianParameter(6.0, 0.1),
    None,
    OrnsteinParameter(6.0, 0.1, 0.1))
  val seasonalParamDaily: Parameters = LeafParameter(
    GaussianParameter(DenseVector(-0.5, -0.3, -0.75, -0.3, -0.3, -0.5), diag(DenseVector.fill(6)(0.1))),
    None,
    OrnsteinParameter(
      theta = DenseVector(-1.2, -1.0, -1.0, -0.5, -0.5, -0.7, -0.5, -0.7),
      alpha = DenseVector.fill(6)(0.1),
      sigma = DenseVector.fill(6)(0.2)))

  val p = poissonParam |+| seasonalParamDaily

  val poisson = PoissonModel(stepOrnstein)
  val daily = SeasonalModel(24, 3, stepOrnstein)
  val poissonMod = poisson |+| daily

  val times = (1 to 169).map(_.toDouble).toList
  val sims = simData(times, poissonMod(p))


  val pw = new PrintWriter("simCars.csv")
  pw.write(sims.
    map(data => s"${data.t}, ${data.observation}").
    mkString("\n"))
  pw.close()
}
