package edu.cmu.cs.oak.nodes

import edu.cmu.cs.oak.value.SymbolValue

case class SymbolNode(sv: SymbolValue) extends DNode {
  def getChildren(): Seq[DNode] = null

  override def toXml = {
    <symbol>
      {sv.toString}
    </symbol>
  }
  
  override def ifdefy(): List[String] = List("Σ")

}