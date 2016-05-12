package edu.cmu.cs.oak.value

import scala.annotation.elidable.ASSERTION


case class ObjectValue(name: String, stereotype: ClassDef) extends OakValue {

  val fields = new ArrayValue
  
  def getName(): String = name
  
  def getClassdef(): ClassDef = stereotype
  
  def getFields(): ArrayValue = fields
  
  def get(fieldKey: String): OakValue = {
    //assert(stereotype.getFields.contains(fieldKey))
    return fields.get(StringValue(fieldKey))
  }
  
  def set(fieldKey: String, value: OakValue): Unit = {
    assert(stereotype.getFields.contains(fieldKey))
    fields.set(StringValue(fieldKey), value)
  }
  
}