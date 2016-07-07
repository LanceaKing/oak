package edu.cmu.cs.oak.value

import com.caucho.quercus.Location

case class StringValue(value: String, var file: String, var lineNr: Int) extends OakValue {

  override def toString() = value
override def isEmpty() = false
  def this(value: String, location: Location) {
    this(value, "", 0)
    this.setLocation(location)
  }

  def setLocation(location: Location) {
    if (location != null) {
      this.file = location.getFileName()
      this.lineNr = location.getLineNumber()
    } else {
      this.file = ""
      this.lineNr = 0
    }

  }

  def getFileName(): String = file

  def getLineNr(): Int = lineNr

}