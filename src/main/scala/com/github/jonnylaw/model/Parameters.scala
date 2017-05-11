package com.github.jonnylaw.model

import breeze.linalg.{DenseMatrix, DenseVector, diag}
import breeze.linalg.eigSym._
import breeze.stats.covmat
import breeze.stats.distributions._
import breeze.numerics.{exp, sqrt}
import cats._
import cats.implicits._

sealed trait Parameters { self =>
  def sum(that: Parameters): Error[Parameters]

  def perturb(delta: Double): Rand[Parameters]

  /**
    * Adds the value delta to the parameters
    */
  def add(delta: DenseVector[Double]): Parameters = self match {
    case BranchParameter(l, r) =>
      Parameters.branchParameter(l.add(delta(0 to l.length - 1)), r.add(delta(l.length to -1)))
    case LeafParameter(scale, sdeParam) => scale match {
      case Some(v) => 
        val sde = sdeParam.add(delta(1 to -1))
        Parameters.leafParameter(Some(v + delta(0)), sde)
      case None =>
        Parameters.leafParameter(None, sdeParam.add(delta))
    }
    case EmptyParameter => Parameters.emptyParameter
  }

  def flatten: Seq[Double]

  def length: Int = this.flatten.length

  /**
    * Propose a new value of the parameters using a Multivariate Normal distribution
    * Using the cholesky decomposition of the covariance matrix
    * @param cholesky the cholesky decomposition of the covariance of the proposal distribution
    * @return a distribution over the parameters which can be drawn from
    */
  def perturbMvn(chol: DenseMatrix[Double])(implicit rand: RandBasis = Rand): Rand[Parameters] = new Rand[Parameters] {
    def draw = {
      val innov = chol * DenseVector.rand(chol.cols, rand.gaussian(0, 1))
      self.add(innov)
    }
  }

  /**
    * Propose a new value of the parameters using a Multivariate Normal distribution
    * using the eigenvalue decomposition of the covariance matrix
    * @param eigen the eigenvalue decomposition of the covariance matrix of the proposal distribution
    * @return a distribution over the parameters which can be drawn from
    */
  def perturbMvnEigen(eigen: EigSym[DenseVector[Double], DenseMatrix[Double]], scale: Double)
    (implicit rand: RandBasis = Rand): Rand[Parameters] = new Rand[Parameters] {
    def draw = {
      val q = scale * eigen.eigenvectors * diag(eigen.eigenvalues.mapValues(x => sqrt(x)))
      val innov = q * DenseVector.rand(eigen.eigenvalues.length, rand.gaussian(0, 1))
      self.add(innov)
    }
  }

  def size: Int = self match {
    case _: LeafParameter => 1
    case EmptyParameter => 0
    case BranchParameter(l, r) => l.size + r.size
  }

  /**
    * Get the leaf parameter at the ith node from the left
    */
  def getNode(i: Int): LeafParameter = self match {
    case p: LeafParameter => p
    case BranchParameter(l, r) if (l.size <= i) => r.getNode(i - l.size)
    case BranchParameter(l, r) if (l.size > i) => l.getNode(i)
  }

  def mapDbl(f: Double => Double): Parameters = self match {
    case LeafParameter(v, sdeParam) => Parameters.leafParameter(v.map(f), sdeParam.mapDbl(f))
    case BranchParameter(l, r) => Parameters.branchParameter(l.mapDbl(f), r.mapDbl(f))
    case EmptyParameter => EmptyParameter
  }
}
case class LeafParameter(scale: Option[Double], sdeParam: SdeParameter) extends Parameters {

  def sum(that: Parameters): Error[Parameters] = that match {
    case LeafParameter(otherScale, sde) =>
      for {
        sdeSum <- sdeParam sum sde
        scaleSum = scale flatMap (v1 => otherScale map (v2 => v1 + v2))
      } yield Parameters.leafParameter(scaleSum, sdeSum)
    case _ => Right(throw new Exception(s"Can't sum LeafParameter and $that"))
  }

  def flatten: Seq[Double] = scale match {
    case Some(v) => Vector(v) ++ sdeParam.flatten
    case None => sdeParam.flatten
  }

  def perturb(delta: Double): Rand[Parameters] = {
    for {
      sde <- sdeParam.perturb(delta)
      innov <- Gaussian(0.0, delta)
      v = scale.map(_ + innov)
    } yield Parameters.leafParameter(v, sde)
  }
}

case class BranchParameter(left: Parameters, right: Parameters) extends Parameters {
  def sum(that: Parameters): Error[Parameters] = that match {
    case BranchParameter(l, r) => 
      for {
        sumLeft <- left sum l
        sumRight <- right sum r
      } yield Parameters.branchParameter(sumLeft, sumRight)
    case _ => Right(throw new Exception(s"Can't add BranchParameter and $that"))
  }

  def perturb(delta: Double): Rand[Parameters] = {
    for {
      l <- left.perturb(delta)
      r <- right.perturb(delta)
    } yield Parameters.branchParameter(l, r)
  }

  def flatten: Seq[Double] = left.flatten ++ right.flatten
}

case object EmptyParameter extends Parameters {
  def perturb(delta: Double): Rand[Parameters] = Rand.always(Parameters.emptyParameter)
  def sum(that: Parameters): Error[Parameters] = Right(that)
  def flatten = Vector()
  // def map(f: Double => Double): Parameters = EmptyParameter
}

object Parameters {
  def leafParameter(scale: Option[Double], sdeParam: SdeParameter): Parameters = {
    LeafParameter(scale, sdeParam)
  }

  def branchParameter(lp: Parameters, rp: Parameters): Parameters = {
    BranchParameter(lp, rp)
  }

  def emptyParameter: Parameters = EmptyParameter

  /**
    * A monoid to compose parameter values
    */
  implicit def composeParameterMonoid = new Monoid[Parameters] {
    def combine(lp: Parameters, rp: Parameters): Parameters = (lp, rp) match {
      case (EmptyParameter, r) => r
      case (l, EmptyParameter) => l
      case _ => Parameters.branchParameter(lp, rp)
    }

    def empty: Parameters = Parameters.emptyParameter
  }

  /**
    * Sum parameter values
    */
  def sumParameters(lp: Parameters, rp: Parameters): Error[Parameters] = lp sum rp

  /**
    * Calculate the mean of the parameter values
    */
  def mean(params: Seq[Parameters]): Error[Parameters] = {
    val sum = params.foldLeft(Right(Parameters.emptyParameter): Error[Parameters])((a, b) =>
      a.flatMap(Parameters.sumParameters(_, b)))

    sum.map(_.mapDbl(_/params.length))
  }

  def proposeIdent: Parameters => Rand[Parameters] = p => new Rand[Parameters] {
    def draw = p
  }

  def perturb(delta: Double): Parameters => Rand[Parameters] = p => {
    p.perturb(delta)
  }

  def perturbMvn(chol: DenseMatrix[Double]) = { (p: Parameters) =>
    p.perturbMvn(chol)
  }

  def perturbMvnEigen(eigen: EigSym[DenseVector[Double], DenseMatrix[Double]], scale: Double) = { (p: Parameters) =>
    p.perturbMvnEigen(eigen, scale)
  }

  /**
    * Check if two parameter trees are isomorphic in shape when traversed from the left
    */
  def isIsomorphic(p: Parameters, p1: Parameters): Boolean = {
    p.flatten == p1.flatten
  }

  /**
    * Calculate the covariance of a sequence of parameters
    */
  def covariance(samples: Seq[Parameters]): DenseMatrix[Double] = {
    val dim = samples.head.flatten.size
    val m = new DenseMatrix(samples.size, dim, samples.map(_.flatten.toArray).toArray.transpose.flatten)
    covmat.matrixCovariance(m)
  }
}
