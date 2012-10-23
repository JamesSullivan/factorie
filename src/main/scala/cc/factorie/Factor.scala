/* Copyright (C) 2008-2010 University of Massachusetts Amherst,
   Department of Computer Science.
   This file is part of "FACTORIE" (Factor graphs, Imperative, Extensible)
   http://factorie.cs.umass.edu, http://code.google.com/p/factorie/
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
    http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License. */

package cc.factorie

import scala.collection.mutable.{ArrayBuffer, HashMap, HashSet, ListBuffer, FlatHashTable}
import scala.util.{Random,Sorting}
import scala.util.Random
import scala.math
import scala.util.Sorting
import cc.factorie.la._
import cc.factorie.util.Substitutions
import java.io._

/** A single factor in a factor graph:  neighboring variables and methods for getting values and their score.
    @author Andrew McCallum */
trait Factor extends Ordered[Factor] {
  type StatisticsType <: Any
  /** Returns the collection of variables neighboring this factor. */
  def variables: Seq[Variable]
  /** The number of variables neighboring this factor. */
  def numVariables: Int
  def variable(index: Int): Variable
  def touches(variable:Variable): Boolean = this.variables.contains(variable)
  def touchesAny(variables:Iterable[Variable]): Boolean = variables.exists(touches(_))
  
  // Getting the score
  
  /** This factors contribution to the unnormalized log-probability of the current possible world. */
  def currentScore: Double
  /** The ability to score a Values object is now removed, and this is its closest alternative. */
  def assignmentScore(a:Assignment): Double
  /** Return the score for Factors whose values can be represented as a Tensor, otherwise throw an Error.
      For Factors/Family in which the Statistics are the values, this method simply calls valuesScore(Tensor). */
  def valuesScore(tensor:Tensor): Double = throw new Error("This Factor class does not implement valuesScore(Tensor).")
  // Do not declare statisticsScore(tensor:Tensor) here, because it should be abstract in TensorFactor2, etc.

  // Getting the statistics
  
  /** Return this Factor's sufficient statistics of the current values of the Factor's neighbors. */
  def currentStatistics: StatisticsType = throw new Error("This Factor class does not implement statistics.") // currentAssignment.asInstanceOf[StatisticsType] // A dummy default for statistics
  /** Return this Factor's sufficient statistics for the values in the Assignment. */
  def assignmentStatistics(a:Assignment): StatisticsType = throw new Error("This Factor class does not implement statistics.") // currentAssignment.asInstanceOf[StatisticsType] // A dummy default for statistics
  /** Given a Tensor representation of the values, return a Tensor representation of the statistics.  We assume that if the values have Tensor representation that the StatisticsType does also.
      Note that (e.g. in BP) the Tensor may represent not just a single value for each neighbor, but a distribution over values */
  def valuesStatistics(tensor:Tensor): Tensor = throw new Error("This Factor class does not implement valuesStatistics(Tensor).")
  /** True iff the statistics are the values (without transformation), e.g. valuesStatistics simply returns its argument. */
  def statisticsAreValues: Boolean = false
  
  /** Return the score and statistics of the current neighbor values; this method enables special cases in which it is more efficient to calculate them together. */
  def currentScoreAndStatistics: (Double,StatisticsType) = (currentScore, currentStatistics)
  def assignmentScoreAndStatistics(a:Assignment): (Double,StatisticsType) = (assignmentScore(a), assignmentStatistics(a))
  def valuesScoreAndStatistics(t:Tensor): (Double,Tensor) = (valuesScore(t), valuesStatistics(t))

  // Getting Assignments

  /** Return a record of the current values of this Factor's neighbors. */
  def currentAssignment: Assignment

  /** Return an object that can iterate over all value assignments to the neighbors of this Factor */
  def valuesIterator: ValuesIterator
  
//  def valuesIterator(varying:Set[Variable]): Iterator[Values]
  /** Return a copy of this factor with some neighbors potentially substituted according to the mapping in the argument. */
  //def copy(s:Substitutions): Factor
  // Implement Ordered, such that worst (lowest) scores are considered "high"
  def compare(that: Factor) = {val d = that.currentScore - this.currentScore; if (d > 0.0) 1 else if (d < 0.0) -1 else 0}
  /** In order to two Factors to satisfy "equals", the value returned by this method for each Factor must by "eq" . */
  def equalityPrerequisite: AnyRef = this.getClass
  // Implement equality based on class assignability and Variable contents equality
  //override def canEqual(other: Any) = (null != other) && other.isInstanceOf[Factor]; // TODO Consider putting this back in
  override def equals(other: Any): Boolean = other match {
    case other:Factor =>
      (this eq other) || ((this.equalityPrerequisite eq other.equalityPrerequisite)
                          && (this.hashCode == other.hashCode)
                          && forallIndex(numVariables)(i =>
                            (this.variable(i) eq other.variable(i)) || // TODO Consider getting rid of this, and just using == all the time.
                            (this.variable(i).isInstanceOf[Vars[_]] && this.variable(i) == other.variable(i))))
                            // TODO with the == above, some Vars classes should implement equals based on sameContents
    case _ => false
  }
  var _hashCode = -1
  override def hashCode: Int = {
    if (_hashCode == -1) {
      _hashCode = getClass.hashCode
      var i = 0
      while (i < numVariables) {
        val v = variable(i);
        _hashCode += 31*i + (if (v eq null) 0 else v.hashCode)
        i += 1
      }
    }
    _hashCode
  }
  def factorName = "Factor"
  override def toString: String = variables.mkString(factorName+"(", ",", ")")
}

/** Created by a method in a Factor to iterate over a (sub)set of assignments. */
trait ValuesIterator extends Iterator[Assignment] {
  def factor: Factor
  def score: Double
  def valuesTensor: Tensor
}

/** An iterable collection for efficiently holding a single Factor.
    Used in various Template classes and in an implicit conversion from Factor to IterableSingleFactor. */
class IterableSingleFactor[F<:Factor](val factor:F) extends Iterable[F] {
  def iterator = Iterator.single(factor)
  override def size = 1
  override def head = factor
}


// This comment mostly discusses an old (removed) version of FACTORIE in which Factors could have "inner" Factors.
/** A Factor is a Model because it can return a factor (itself) and a score.
    A Model is not a Factor because Factors *must* be able to list all the variables they touch;
     (this is part of how they are de-duplicated);
     yet a model may only generate Factors on the fly in response to query variables.
    Factors are deduplicated.  Models are not; 
     multiple models may each contribute Factors, all of which define the factor graph.  
    Models can have inner Models, which are used to obtain factors from each.
     Typically all factors from all inner models are summed together.
     But a "Case" model may exist, which simply will not return factors that are not in effect;
     that is, factors from an inner model that are not in effect will never be seen; 
     whereas inner factors may be seen but know to point to the outer factor that knows how to handle them.  
    Factors can have inner factors, which are used to calculate its score; often not summed.
     This is a special case of a Model having inner Models.
    When you ask an inner Factor for its score, it returns the score of the outer Factor to which it contributes.
     (Is this right?  Perhaps not.)
    When you ask an inner Model for its score, it returns its score alone.
    */

