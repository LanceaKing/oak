package edu.cmu.cs.oak.nodes

import edu.cmu.cs.oak.env.Constraint

case class SelectNode(mapc: Map[DNode, Constraint]) extends DNode {
  
  override def toXml() = {
    <Select>
			{for ((v, c) <- mapc) yield { 
          <Condition text={c.get().toString()}>
						{v.toXml()}
					</Condition>
        }
      }
    </Select>
  }
  
    override def ifdefy(): List[String] = {
    var sequence = List[String]()
    mapc.slice(0, mapc.size - 1)foreach {
      case (v, c) => {
        sequence ++= List(s"<!-- #ifdef ${c.toString()} -->")
        sequence ++= v.ifdefy()
        sequence ++= List(s"<!-- #else -->")
        
      }
    }
    sequence ++= List(s"<!-- #ifdef ${mapc.last._2.toString()} -->")
    sequence ++= mapc.last._1.ifdefy()
    sequence ++= List(s"<!-- #endif -->")
   
    sequence
  }
    
  override def isEmpty() = (mapc.size == 0)
  
  override def getChildren() = mapc.keys.toSeq
}
