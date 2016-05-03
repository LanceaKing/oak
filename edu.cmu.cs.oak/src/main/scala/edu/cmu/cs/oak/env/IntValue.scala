package edu.cmu.cs.oak.env

case class IntValue(value: Int) extends NumericValue {
  override def +(v: NumericValue): NumericValue = {
    return v match {
      case i: IntValue => IntValue(value + i.getValue)
      case d: DoubleValue => DoubleValue(value + d.getValue)
    }
  }
  override def -(v: NumericValue): NumericValue = {
    return v match {
      case i: IntValue => IntValue(value - i.getValue)
      case d: DoubleValue => DoubleValue(value - d.getValue)
    }
  }
  override def *(v: NumericValue): NumericValue = {
    return v match {
      case i: IntValue => IntValue(value * i.getValue)
      case d: DoubleValue => DoubleValue(value * d.getValue)
    }
  }
  override def /(v: NumericValue): NumericValue = {
    return v match {
      case i: IntValue => IntValue(value / i.getValue)
      case d: DoubleValue => DoubleValue(value / d.getValue)
    }
  }
  override def %(v: NumericValue): NumericValue = {
    return v match {
      case i: IntValue => IntValue(value % i.getValue)
      case d: DoubleValue => DoubleValue(value % d.getValue)
    }
  }

  override def <(v: NumericValue): BooleanValue = {
    return v match {
      case i: IntValue => BooleanValue(value < i.getValue)
      case d: DoubleValue => BooleanValue(value < d.getValue)
    }
  }

  override def >(v: NumericValue): BooleanValue = {
    return v match {
      case i: IntValue => BooleanValue(value > i.getValue)
      case d: DoubleValue => BooleanValue(value > d.getValue)
    }
  }
  def getValue: Int = value
}