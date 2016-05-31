package examples

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import java.io.{File, PrintWriter}
import akka.stream.scaladsl._
import scala.concurrent.{duration, Await}
import scala.concurrent.duration._
import akka.util.ByteString

import model._
import model.Model._
import model.Filtering._
import model.POMP.{PoissonModel, SeasonalModel, LinearModel, BernoulliModel}
import model.DataTypes._
import model.{State, Model}
import model.SimData._
import model.Utilities._
import model.State._
import model.Parameters._
import model.StateSpace._
import java.io.{PrintWriter, File}
import breeze.stats.distributions.Gaussian
import breeze.linalg.{DenseVector, diag}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import breeze.numerics.exp


object SimulateSeasonalPoisson extends App {
  val poissonParams = LeafParameter(
    GaussianParameter(0.3, 0.5),
    None,
    BrownianParameter(0.1, 1.0))
  val seasonalParams = LeafParameter(
    GaussianParameter(DenseVector.fill(6)(0.3),
      diag(DenseVector.fill(6)(0.5))),
    None,
    OrnsteinParameter(DenseVector.fill(6)(0.3), DenseVector.fill(6)(0.5), DenseVector.fill(6)(0.1)))

  val params = poissonParams |+| seasonalParams
  val mod = Model.op(PoissonModel(stepBrownian), SeasonalModel(24, 3, stepOrnstein))

  val times = (1 to 100).map(_.toDouble).toList
  val sims = simData(times, mod(params))

  val pw = new PrintWriter("seasonalPoissonSims.csv")
  pw.write(sims.mkString("\n"))
  pw.close()
}


object FilteringSeasonalPoisson extends App {
  val poissonParams = LeafParameter(
    GaussianParameter(0.3, 0.5),
    None,
    BrownianParameter(0.1, 1.0))
  val seasonalParams = LeafParameter(
    GaussianParameter(DenseVector.fill(6)(0.3),
      diag(DenseVector.fill(6)(0.5))),
    None,
    OrnsteinParameter(DenseVector.fill(6)(0.3), DenseVector.fill(6)(0.5), DenseVector.fill(6)(0.1)))

  val params = poissonParams |+| seasonalParams
  val mod = Model.op(PoissonModel(stepBrownian), SeasonalModel(24, 3, stepOrnstein))

  val data = scala.io.Source.fromFile("seasonalPoissonSims.csv").getLines.
    map(a => a.split(",")).
    map(rs => Data(rs(0).toDouble, rs(1).toDouble, None, None, None)).
    toVector

  val filtered = bootstrapPf(1000, data, mod)(params)

  val pw = new PrintWriter("seasonalPoissonFiltered.csv")
  pw.write(filtered.mkString("\n"))
  pw.close()
}

object DetermineComposedParams extends App {
  implicit val system = ActorSystem("DeterminePoissonParams")
  implicit val materializer = ActorMaterializer()

  val poissonParams = LeafParameter(
    GaussianParameter(0.3, 0.5),
    None,
    BrownianParameter(0.1, 1.0))
  val seasonalParams = LeafParameter(
    GaussianParameter(DenseVector.fill(6)(0.3),
      diag(DenseVector.fill(6)(0.5))),
    None,
    OrnsteinParameter(DenseVector.fill(6)(0.3), DenseVector.fill(6)(0.5), DenseVector.fill(6)(0.1)))

  val params = poissonParams |+| seasonalParams
  val mod = Model.op(PoissonModel(stepBrownian), SeasonalModel(24, 3, stepOrnstein))

  val data = scala.io.Source.fromFile("seasonalPoissonSims.csv").getLines.
    map(a => a.split(",")).
    map(rs => Data(rs(0).toDouble, rs(1).toDouble, None, None, None)).
    toVector

  val mll = pfMllFold(data, mod) _

  runPmmhToFile("poisson", 2, params, mll, Parameters.perturb(0.1), 200, 10000)
}

object FilterOnline extends App {
  implicit val system = ActorSystem("StreamingPoisson")
  implicit val materializer = ActorMaterializer()

  // define the model
    val poissonParams = LeafParameter(
    GaussianParameter(0.3, 0.5),
    None,
    BrownianParameter(0.1, 1.0))
  val seasonalParams = LeafParameter(
    GaussianParameter(DenseVector.fill(6)(0.3),
      diag(DenseVector.fill(6)(0.5))),
    None,
    OrnsteinParameter(DenseVector.fill(6)(0.3), DenseVector.fill(6)(0.5), DenseVector.fill(6)(0.1)))

  val params = poissonParams |+| seasonalParams
  val unparamMod = Model.op(PoissonModel(stepBrownian), SeasonalModel(24, 3, stepOrnstein))
  val mod = unparamMod(params)

  // we can simulate from the process as a stream
  val initialObservation = simStep(mod.x0.draw, 0.0, 1.0, mod)
  val dt = 0.1 // specify the time step of the process

  // unfold holds onto the first item in the Some Tuple and uses it in the next application of the anonymous function
  val observations = Source.unfold(initialObservation){d =>
    Some((simStep(d.sdeState.get, d.t + dt, dt, mod), d))
  }.
    take(100)

  // write the observations to a file
    observations.
      map(a => ByteString(s"$a\m")).
      runWith(FileIO.toFile(new File("OnlineComposedModel.csv")))

  // particles and initial state for particle filter
  val n = 1000
  val initState = Vector(PfState(0.0, None, Vector.fill(n)(mod.x0.draw), State.zero, IndexedSeq[CredibleInterval]()))

    // use fold to filter a stream
    // fold does not return immediately, but filterStep can be modified to
    // perform a side effect and print or write each filtered observation
  observations.
    fold(initState)((d, y) => filterStep(y, d, mod, 200)).
    drop(1).
    map(a => ByteString(s"$a\n")).
    runWith(FileIO.toFile(new File("OnlineComposedModelFiltered.csv")))

  Thread.sleep(25000) // sleep for 25 seconds

  system.shutdown
}

object cars extends App {
  implicit val system = ActorSystem("FitCars")
  implicit val materializer = ActorMaterializer()

  val data = scala.io.Source.fromFile("cars.csv").getLines.toVector.
    map(a => a.split(",")).
    map(rs => Data(rs(1).toDouble, rs(2).toDouble, None, None, None))

  // define the model
    val poissonParams = LeafParameter(
    GaussianParameter(0.3, 0.5),
    None,
    BrownianParameter(0.1, 1.0))
  val seasonalParams = LeafParameter(
    GaussianParameter(DenseVector.fill(6)(0.3),
      diag(DenseVector.fill(6)(0.5))),
    None,
    OrnsteinParameter(DenseVector.fill(6)(0.3), DenseVector.fill(6)(0.5), DenseVector.fill(6)(0.1)))

  val initParams = poissonParams |+| seasonalParams
  val unparamMod = Model.op(PoissonModel(stepBrownian), SeasonalModel(24, 3, stepOrnstein))

  val iterations = 10000
  val particles = Vector(100, 200, 500, 1000)

  val mll = pfMll(data.sortBy(_.t), unparamMod) _

  Source(particles.zip(1 to 4)).
    mapAsync(parallelism = 4){ case (n, chain) =>
      val iters = ParticleMetropolis(mll(n), initParams, Parameters.perturb(0.1)).iters.draw

      println(s"""Running chain $chain with $particles particles, $iterations iterations""")

      iters.
        zip(Source(Stream.from(1))).
        map{ case (x, i) => (i, x.params) }.
        take(iterations).
        map{ case (i, p) => ByteString(s"$i, $p\n") }.
        runWith(FileIO.toFile(new File(s"cars-$iterations-$particles.csv")))
      
      iters.
        via(monitorStream(1000, chain)).
        runWith(Sink.ignore)
    }.
    runWith(Sink.onComplete { _ =>
      system.shutdown()
    })
}
