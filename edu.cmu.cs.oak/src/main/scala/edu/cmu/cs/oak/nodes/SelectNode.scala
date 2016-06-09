package edu.cmu.cs.oak.nodes

import edu.cmu.cs.oak.analysis.inlcude.OutputGraphListener

case class SelectNode(condition: String, v1: DNode, v2: DNode) extends DNode {
  
  override def traverse(listener: OutputGraphListener) {
     listener.addEdge(this.hashCode.toString, v1.hashCode.toString)
     v1.traverse(listener)
     listener.addEdge(this.hashCode.toString, v2.hashCode.toString)
     v2.traverse(listener)
  }
  
  def getChildren(): Seq[DNode] = List(v1, v2)
  
  override def toXml = {
    <Select>
			<Constraint Text={condition} />
      {v1.toXml}
      {v2.toXml}
    </Select>
  }
  
  override def ifdefy(): List[String] = {
    var sequence = List[String]()
    sequence ++= List("#ifdef " + condition)
    sequence ++= v1.ifdefy()
    sequence ++= List("#else")
    sequence ++= v2.ifdefy()
    sequence ++= List("#endif")
    sequence
  }
}