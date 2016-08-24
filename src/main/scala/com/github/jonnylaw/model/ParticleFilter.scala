package com.github.jonnylaw.model

import com.github.jonnylaw.model.POMP._
import com.github.jonnylaw.model.Utilities._
import com.github.jonnylaw.model.DataTypes._
import com.github.jonnylaw.model.State._
import com.github.jonnylaw.model.SimData._
import scala.language.higherKinds._

import breeze.stats.distributions.{Rand, Uniform, Multinomial}
import breeze.stats.distributions.Rand._
import breeze.numerics.exp
import breeze.linalg.DenseVector
import ParticleFilter._

import akka.stream.scaladsl.Source
import akka.stream.scaladsl._

/**
  * Representation of the state of the particle filter, at each step the previous observation time, t0, and 
  * particle cloud, particles, is required to compute forward.
  * The meanState and intervals are recorded in each step, so they can be outputted immediately without having
  * to calculate these from the particle cloud after
  */
case class PfState(
  t: Time,
  observation: Option[Observation],
  particles: Vector[State],
  weights: Vector[LogLikelihood],
  ll: LogLikelihood) {

   override def toString = observation match {
      case Some(y) => s"$t, $y, ${weightedMean(particles, weights).flatten.mkString(", ")}"
      case None => s"$t, ${weightedMean(particles, weights).flatten.mkString(", ")}"
    }

}

trait ParticleFilter {

  /**
    * Abstract function representing an unparameterised model, can be a single model or 
    * a model composition
    */
  val unparamMod: Parameters => Model
  def initialiseState(p: Parameters, particles: Int, t0: Time): PfState = {
      val state = Vector.fill(particles)(unparamMod(p).x0.draw)
      PfState(t0, None, state, Vector.fill(particles)(1.0), 0.0)
  }

  def advanceState(x: Vector[State], dt: TimeIncrement, t: Time)(p: Parameters): Vector[(State, Eta)]
  def calculateWeights(x: Eta, y: Observation)(p: Parameters): LogLikelihood
  def resample: Resample[State]

  /**
    * Step filter
    */
  def stepFilter(s: PfState, y: Data)(p: Parameters): PfState = {
    val dt = y.t - s.t // calculate time between observations

    val unweightedX: Vector[State] = resample(s.particles, s.weights)

    val (x1, eta) = advanceState(unweightedX, dt, y.t)(p).unzip
    val w = eta map (a => calculateWeights(a, y.observation)(p))
    val max = w.max
    val w1 = w map { a => exp(a - max) }

    val ll = s.ll + max + math.log(breeze.stats.mean(w1))

    PfState(y.t, Some(y.observation), x1, w1, ll)
  }

  /**
    * Calculate the log-likelihood
    */
  def llFilter(data: Vector[Data], t0: Time)(particles: Int)(p: Parameters): LogLikelihood = {
    val initState = initialiseState(p, particles, t0)
    data.foldLeft(initState)(stepFilter(_, _)(p)).ll
  }

  /**
    * Run a filter over a vector of data and return a vector of PfState
    * Containing the raw particles and associated weights at each time step
    */
  def accFilter(data: Vector[Data], t0: Time)(particles: Int)(p: Parameters): Vector[PfState] = {
    val initState = initialiseState(p, particles, t0)

    val x = data.scanLeft(initState)(stepFilter(_, _)(p))

    x.tail
  }

  /**
    * Filter the data, but get a vector containing the mean eta, eta intervals, mean state, 
    * and credible intervals of the state
    */
  def filterWithIntervals(data: Vector[Data], t0: Time)(particles: Int)(p: Parameters): Vector[PfOut] = {
    accFilter(data, t0)(particles)(p).map(getIntervals(unparamMod(p)))
  }

  /**
    * Run a filter over a stream of data
    */
  def filter(data: Source[Data, Any], t0: Time)(particles: Int)(p: Parameters): Source[PfState, Any] = {
    val initState = initialiseState(p, particles, t0)

    data.scan(initState)(stepFilter(_, _)(p))
  }
}

object ParticleFilter {
  type Resample[A] = (Vector[A], Vector[LogLikelihood]) => Vector[A]

  /**
    * Transforms PfState into PfOut, including eta, eta intervals and state intervals
    */
  def getIntervals(mod: Model): PfState => PfOut = s => {
    val meanState = weightedMean(s.particles, s.weights)
    val stateIntervals = getAllCredibleIntervals(s.particles, 0.995)
    val etas = s.particles map (x => mod.link(mod.f(x, s.t)).head)
    val meanEta = mod.link(mod.f(meanState, s.t)).head
    val etaIntervals = getOrderStatistic(etas, 0.995)

    PfOut(s.t, s.observation, meanEta, etaIntervals, meanState, stateIntervals)
  }


  /**
    * Return a vector of lag 1 time differences
    * @param x a list of times
    * @return a list of differenced times
    */
  def diff(x: Iterable[Time]): Iterable[TimeIncrement] = {
    (x.tail zip x) map { a => a._1 - a._2 }
  }

  /**
    * Sample integers from 1 to n with replacement according to their associated probabilities
    * @param n a number matching the number of probabilities
    * @param prob a vector of probabilities corresponding to the probability of sampling that integer
    * @return a vector containing the samples
    */
  def sample(n: Int, prob: DenseVector[Double]): Vector[Int] = {
    Multinomial(prob).sample(n).toVector
  }

  /**
    * Given a vector of doubles, returns a normalised vector with probabilities summing to one
    * @param prob a vector of unnormalised probabilities
    * @return a vector of normalised probabilities
    */
  def normalise(prob: Vector[Double]): Vector[Double] = {
    prob map (_/prob.sum)
  }

  def cumsum(x: Vector[Double]): Vector[Double] = {
    val sums = x.foldLeft(Vector(0.0))((acc: Vector[Double], num: Double) => (acc.head + num) +: acc)
    sums.reverse.tail
  }

  /**
    * Multinomial Resampling, sample from a categorical distribution with probabilities
    * equal to the particle weights 
    */
  def multinomialResampling[A](particles: Vector[A], weights: Vector[LogLikelihood]): Vector[A] = {
    val indices = sample(particles.size, DenseVector(weights.toArray))
    indices map { particles(_) }
  }

  /**
    * Produces a histogram output of a vector of Data
    */
  def hist(x: Vector[Int]): Unit = {
    val h = x.
      groupBy(identity).
      toVector.map{ case (n, l) => (n, l.length) }.
      sortBy(_._1)

    h foreach { case (n, count) => println(s"$n: ${Vector.fill(count)("#").mkString("")}") }
  }

  /**
    * Return the value x such that, F(p) = x, where F is the empirical cumulative distribution function over
    * the particles
    */
  def invecdf[A](ecdf: Vector[(A, LogLikelihood)], p: Double): A = {
    ecdf.
      filter{ case (_, w) => w > p }.
      map{ case (x, _) => x }.head
  }

  /**
    * Stratified resampling
    * Sample n ORDERED uniform random numbers (one for each particle) using a linear transformation of a U(0,1) RV
    */
  def stratifiedResampling[A](particles: Vector[A], weights: Vector[LogLikelihood]): Vector[A] = {
    // generate n uniform random numbers
    val n = weights.length
    val u = (1 to n).map(k => (k - 1 + Uniform(0,1).draw) / n).toVector
    val ecdf = particles.zip(cumsum(normalise(weights)))

    u map (invecdf(ecdf, _))
  }

  /**
    * Systematic Resampling
    * Sample n ORDERED numbers (one for each particle), reusing the same U(0,1) variable
    */
  def systematicResampling[A](particles: Vector[A], weights: Vector[LogLikelihood]): Vector[A] = {
    val n = weights.length
    val u = Uniform(0,1).draw
    val k = (1 to n).map(a => (a - 1 + u) / n).toVector
    val ecdf = particles.zip(cumsum(normalise(weights)))

    k map (invecdf(ecdf, _))
  }

  /**
    * Residual Resampling
    * Select particles in proportion to their weights, ie particle xi appears ki = n * wi times
    * Resample m (= n - total allocated particles) particles according to w = n * wi - ki using other resampling technique
    */
  def residualResampling[A](particles: Vector[A], weights: Vector[LogLikelihood]): Vector[A] = {
    val n = weights.length
    val normalisedWeights = normalise(weights)
    val ki = normalisedWeights.
      map (w => math.floor(w * n).toInt)

    val indices = ki.zipWithIndex.
      map { case (n, i) => Vector.fill(n)(i) }.
      flatten
    val x = indices map { particles(_) }
    val m = n - indices.length
    val residualWeights = normalisedWeights.zip(ki) map { case (w, k) => n * w - k }

    val i = sample(m, DenseVector(residualWeights.toArray))
    x ++ (i map { particles(_) })
  }

  /**
    * map2 implementation for Rand
    */
  def map2[A,B,C](ma: Rand[A], mb: Rand[B])(f: (A, B) => C): Rand[C] = {
    for {
      a <- ma
      b <- mb
    } yield f(a, b)
  }

  /**
    * Traverse method for Rand and Vector
    */
  def traverse[A,B](l: Seq[A])(f: A => Rand[B]): Rand[Seq[B]] = {
    l.foldRight(always(Seq[B]()))((a, mlb) => map2(f(a), mlb)(_ +: _))
  }

  /**
    * Sequence, Traverse with the identity
    */
  def sequence[A](l: Seq[Rand[A]]): Rand[Seq[A]] = {
    traverse(l)(a => a)
  }

  /**
    * ReplicateM, fills a Vector with the monad (Rand[A]) and sequences it
    */
  def replicateM[A](n: Int, fa: Rand[A]): Rand[Seq[A]] = {
    sequence(Seq.fill(n)(fa))
  }

    /**
    * Gets credible intervals for a vector of doubles
    * @param samples a vector of samples from a distribution
    * @param interval the upper interval of the required credible interval
    * @return order statistics representing the credible interval of the samples vector
    */
  def getOrderStatistic(samples: Seq[Double], interval: Double): CredibleInterval = {
    val index = math.floor(samples.length * interval).toInt
    val ordered = samples.sorted

    CredibleInterval(ordered(samples.length - index), ordered(index))
  }
}

case class Filter(model: Parameters => Model, resamplingScheme: Resample[State]) extends ParticleFilter {
  
  val unparamMod = model

  def advanceState(states: Vector[State], dt: TimeIncrement, t: Time)(p: Parameters): Vector[(State, Eta)] = {
    val mod = unparamMod(p)

    for {
      x <- states
      x1 = mod.stepFunction(x, dt).draw
      eta = mod.link(mod.f(x1, t))
    } yield (x1, eta)
  }

  def calculateWeights(x: Eta, y: Observation)(p: Parameters): LogLikelihood = {
    val mod = unparamMod(p)
    mod.dataLikelihood(x, y)
  }

  def resample: Resample[State] = resamplingScheme
}

/**
  * In order to calculate Eta in the LGCP model, we need to merge the advance state and transform state functions
  */
case class FilterLgcp(model: Parameters => Model, resamplingScheme: Resample[State], precision: Int) extends ParticleFilter {

  val unparamMod = model

  def calcWeight(x: State, dt: TimeIncrement, t: Time)(p: Parameters): (State, Eta) = {
      val mod = unparamMod(p)
      val x1 = simSdeStream(x, t - dt, dt, precision, mod.stepFunction)
      val transformedState = x1 map (a => mod.f(a.state, a.time))

      (x1.last.state, Vector(transformedState.last, transformedState.map(x => exp(x) * dt).sum))
  }

  def advanceState(x: Vector[State], dt: TimeIncrement, t: Time)(p: Parameters): Vector[(State, Eta)] = {
    x map (calcWeight(_, dt, t)(p))
  }

  def calculateWeights(x: Eta, y: Observation)(p: Parameters): LogLikelihood = {
    val mod = unparamMod(p)
    mod.dataLikelihood(x, y)
  }

  def resample: Resample[State] = resamplingScheme
}
