package com.github.jonnylaw.model

import breeze.numerics.{cos, sin, sqrt, exp, log, lgamma}
import breeze.stats.distributions._
import breeze.linalg.{DenseMatrix, DenseVector}

import cats._
import cats.Semigroup
import cats.implicits._
import cats.data.Reader

import StateSpace._
import Parameters._

trait Model {
  /**
    * The observation model, a function from eta to a distribution over the observations
    * realisations can be produced from the observation model by calling draw
    */
  def observation: Gamma => Rand[Observation]
  /**
    * The linking-function, transforms the state space into the parameter space of the 
    * observation distribution using a possibly non-linear transformation
    */
  def link(x: Gamma): Eta = x

  /**
    * The Linear, deterministic transformation function. f is used to add seasonal factors or
    * other time depending linear transformations
    */ 
  def f(s: State, t: Time): Gamma
  /**
    * An exact or approximate solution to a diffusion process, used to advance the latent state.
    * This function returns a distribution over the next state and can be simulated from
    */
  def sde: Sde
  /**
    * The data likelihood, given the linearly transformed latent state, gamma, and an observation
    * the log-likelihood can be calculated for use in inference algorithms
    */
  def dataLikelihood: (Gamma, Observation) => LogLikelihood

}

object Model {
  def poisson(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => PoissonModel(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def beta(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => BetaModel(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def seasonal(
    period: Int,
    harmonics: Int,
    sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
      case Leaf(param) => SeasonalModel(period, harmonics, sde(param.sdeParam), param)
      case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def linear(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => LinearModel(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def studentsT(sde: UnparamSde, df: Int): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => StudentsTModel(sde(param.sdeParam), df, param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def bernoulli(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => BernoulliModel(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def lgcp(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => LogGaussianCox(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def negativeBinomial(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => NegativeBinomialModel(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  def zeroInflatedPoisson(sde: UnparamSde): Reader[Parameters, Model] = Reader { p => p match {
    case Leaf(param) => ZeroInflatedPoisson(sde(param.sdeParam), param)
    case _ => throw new Exception("Can't build model from branch parameter")
  }}

  /**
    * Models form a semigroup, they can be combined to form a composed model
    */
  implicit def modelSemigroup = new Semigroup[UnparamModel] {
    def combine(m1: UnparamModel, m2: UnparamModel): UnparamModel =
      compose(m1, m2)
  }

  /**
    * Combine two unparameterised models, usually applied with infix notation |+|
    * by importing cats.implicits._, this is not commutative, the observation distribution must 
    * be on the left-hand side of the composition
    * @param mod1 the left-hand model in the composition, if this is a composition of two
    * then the model with the desired observation distribution must be mod1
    * @param mod2 the right-hand model in the composition
    * @return a composed model of mod1 and mod2, which can be composed again
    */
  def compose(mod1: UnparamModel, mod2: UnparamModel): UnparamModel = Reader { p => p match {
    case Branch(lp, rp) => {
      new Model {
        def observation = x => mod1(lp).observation(x)

        override def link(x: Double) = mod1(lp).link(x)

        def f(s: State, t: Time) = s match {
          case Branch(ls, rs) =>
            mod1(lp).f(ls, t) + mod2(rp).f(rs, t)
          case x: Leaf[DenseVector[Double]] =>
            mod1(lp).f(x, t)
          case Empty => 0.0
        }

        def sde: Sde = mod1(lp).sde |+| mod2(rp).sde

        def dataLikelihood = (s, y) => mod1(lp).dataLikelihood(s, y)
      }
    }
    case _ => throw new Exception("Can't Build composed model from Leaf Parameter")
  }}
}

/**
  * Generalised student t model
  * @param stepFun the diffusion process solution to use for this model
  * @return an model of the Student-T model, which can be composed with other models
  */
private final case class StudentsTModel(sde: Sde, df: Int, p: ParamNode) extends Model {
  def observation = x => p.scale match {
    case Some(logv) => {
      val v = exp(logv)
      StudentsT(df) map (a => a * v + x)
    }
    case None => throw new Exception("No scale parameter provided to Student T Model")
  }

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (eta, y) => p.scale match {
    case Some(logv) => {
      val v = exp(logv) // convert v to the correct scale
      1/v * StudentsT(df).logPdf((y - eta) / v)
    }
    case None => throw new Exception("No scale parameter provided to Student T Model")
  }
}

/**
  * Negative Binomial Model for Overdispersed Data, the mean (mu > 0) is the exponential of the 
  * latent state. The variance is equal to mu + mu^2 / size and proportional to the mean 
  */
private final case class NegativeBinomialModel(sde: Sde, p: ParamNode) extends Model {
  def observation = x => p.scale match {
    case Some(logv) => {
      val size = exp(logv)
      val prob = link(x) / (size + link(x))

      for {
        lambda <- Gamma(size, prob / (1-prob))
        x <- Poisson(lambda)
      } yield x.toDouble
    }
    case None => throw new Exception("No scale parameter provided to Negativebinomial Model")
  }

  override def link(x: Gamma) = exp(x)

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (x, y) => p.scale match {
    case Some(logv) => {
      val size = exp(logv)
      val mu = link(x)

      lgamma(size + y.toInt) - lgamma(y.toInt + 1) - lgamma(size) +
      size * math.log(size / (mu + size)) + y.toInt * math.log(mu / (mu + size))
    }
    case None => throw new Exception("No scale parameter provided to Negativebinomial Model")
  }
}

/**
  * A seasonal model
  * @param period the period of the seasonality
  * @param harmonics the number of harmonics to use in the seasonal model
  * @param sde a solution to a diffusion process representing the latent state
  */
private final case class SeasonalModel(
  period: Int, 
  harmonics: Int, 
  sde: Sde, p: ParamNode) extends Model {

  def observation = x => p.scale match {
    case Some(logv) => {
      val v = exp(logv)
      Gaussian(x, v)
    }
    case None => throw new Exception("No SD parameter provided to SeasonalModel")
  }

  def buildF(harmonics: Int, t: Time): DenseVector[Double] = {
    val frequency = 2 * math.Pi / period
    val res = (1 to harmonics).toArray.
      flatMap (a => Array(cos(frequency * a * t), sin(frequency * a * t)))

    DenseVector(res)
  }

  def f(s: State, t: Time) = s.fold(0.0)(x => buildF(harmonics, t) dot x)(_ + _)

  def dataLikelihood = (x, y) => p.scale match {
    case Some(logv) => {
      val v = exp(logv)
      Gaussian(x, v).logPdf(y)
    }
    case None => throw new Exception("No SD parameter provided to SeasonalModel")
  }
}

  /**
    * A linear unparameterised model
    * @param sde a solution to a diffusion process representing the evolution of the latent state
    * @return an UnparamModel which can be composed with other models
    */
private final case class LinearModel(sde: Sde, p: ParamNode) extends Model {
  def observation = x => p.scale match {
    case Some(logv) => {
      val v = exp(logv)
      Gaussian(x, v)
    }
    case None => throw new Exception("Must provide SD parameter for LinearModel")
  }
  
  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (x, y) => p.scale match {
    case Some(logv) => {
      val v = exp(logv)
      Gaussian(x, v).logPdf(y)
    }
    case None => throw new Exception("Must provide SD parameter for LinearModel")
  }
}

/**
  * The Poisson unparameterised model with a one dimensional latent state
  * @param sde a solution to a diffusion process representing the evolution of the latent space
  * @return a Poisson UnparamModel which can be composed with other UnparamModels
  */
private final case class PoissonModel(sde: Sde, p: ParamNode) extends Model {
  def observation = x => Poisson(link(x)) map (_.toDouble): Rand[Double]

  override def link(x: Double) = exp(x)

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (state, y) => Poisson(link(state)).logProbabilityOf(y.toInt)
}

/**
  * The zero inflated Poisson model is useful for count data displaying excess zeros
  * The rate, eta(t) is the expected Poisson count at time t, and the scale parameter 
  * is the probability of extra zeros (so must lie between 0 and 1)
  */
private final case class ZeroInflatedPoisson(sde: Sde, params: ParamNode) extends Model {
  def observation = x => params.scale match {
    case Some(v) => {
      val p = exp(v) / (1 + exp(v))
      for {
        u <- Uniform(0, 1)
        nonZero <- Poisson(link(x))
        next = if (u < p) 0 else nonZero
      } yield next
    }
    case None => throw new Exception("Must provide probability parameter for zero inflated Poisson Model")
  }

  override def link(x: Double) = exp(x)

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (state, y) =>  params.scale match {
    case Some(v) => {
      val p = exp(v) / (1 + exp(v)) // transform the "scale" to be between zero and one
      if (y.toInt == 0) {
        log(p + (1 - p) * exp(-exp(state)))
      } else {
        -log(1 + exp(v)) + y.toInt * state - exp(state) - lgamma(y.toInt + 1)
      }
    }
    case None => throw new Exception("Must provide probability parameter for zero inflated Poisson Model")
  }
}

/**
  * The bernoulli model with a one dimensional latent state
  * @param sde a solution to a diffusion process 
  */
private final case class BernoulliModel(sde: Sde, p: ParamNode) extends Model {
  def observation = p => Uniform(0, 1).map(_ < link(p)).map(a => if (a) 1.0 else 0.0)

  override def link(x: Gamma) = {
    if (x > 6) {
      1.0
    } else if (x < -6) {
      0.0
    } else {
      1.0/(1 + exp(-x))
    }
  }

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (p, y) => {
    if (y == 1.0) {
      if (link(p) == 0.0) -1e99 else log(link(p))
    } else {
      if (link(p) == 1.0) -1e99 else log(1-link(p))
    }
  }
}

private final case class BetaModel(sde: Sde, p: ParamNode) extends Model {
  def  observation = gamma => p.scale match {
    case Some(beta) => new Beta(link(gamma), beta)
    case None => throw new Exception("Must provide shape parameter for Beta Model")
  }

  override def link(x: Gamma) = exp(-x)

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = (gamma, y) => {
    val b = new Beta(link(gamma), 1.0)
    b.logPdf(y)
  }
}

// private final case class LogNormal(sde: Sde, p: ParamNode) extends Model {

// }

/**
  * The Log-Gaussian Cox-Process is used to model time to event data with 
  * log-gaussian varying hazard rate
  */
private final case class LogGaussianCox(sde: Sde, p: ParamNode) extends Model {
  def observation = ???

  def f(s: State, t: Time) = s.fold(0.0)((x: DenseVector[Double]) => x(0))(_ + _)

  def dataLikelihood = ??? // (lambda, y) => lambda.head - lambda(1)
}
