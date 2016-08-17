package edu.cmu.cs.oak.value

case class BooleanValue(v:Boolean) extends OakValue {
  
  // Syntactic sugar
  def &&(v2:BooleanValue): BooleanValue = BooleanValue(v && v2.value)
  def ||(v2:BooleanValue): BooleanValue = BooleanValue(v || v2.value)
  def not(): BooleanValue = BooleanValue(!v)
  def value = v
  override def isEmpty() = false
  override def toString(): String = {
    v match {
      case true => "true"
      case false => "false"
    }
  }
 }