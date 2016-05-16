package edu.cmu.cs.oak.value

case class OakVariable(name: String) extends OakValue {
  def getName(): String = name
  override def toXml = {
    <reference>
			{name}
    </reference>
  }
}