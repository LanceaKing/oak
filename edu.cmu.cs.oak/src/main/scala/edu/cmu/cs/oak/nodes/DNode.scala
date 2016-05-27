package edu.cmu.cs.oak.nodes

import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.Choice
import edu.cmu.cs.oak.value.SymbolValue
import edu.cmu.cs.oak.value.OakValueSequence
import edu.cmu.cs.oak.value.NumericValue
import edu.cmu.cs.oak.value.StringValue
import edu.cmu.cs.oak.value.BooleanValue
import edu.cmu.cs.oak.value.ArrayValue
import java.util.Collection
import scala.collection.mutable.ListBuffer
import edu.cmu.cs.oak.value.IntValue
import edu.cmu.cs.oak.value.DoubleValue
import edu.cmu.cs.oak.nodes.SymbolNode
import edu.cmu.cs.oak.analysis.inlcude.OutputGraphListener

/**
 * Model for (symbolic) output of a symbolically executed PHP script.
 *
 * @author Stefan Muehlbauer <smuhlbau@andrew.cmu.edu>
 */
trait DNode {

  /**
   * Sequence of all child nodes of the current node.
   *
   * @return Sequence of child nodes
   */
  def getChildren(): Seq[DNode]

  /**
   * Recursively serializes a DNode-based tree to XML.
   *
   * @return scala.xml.Elem XML node
   */
  def toXml(): scala.xml.Elem
  
  def traverse(listener: OutputGraphListener) 

  /**
   * Returns a sequence of output strings mixed with #ifdef annotations
   * @return List of output strings
   */
  def ifdefy(): List[String]

  /**
   * Compare method for DNodes.
   * 
   * @param that DNode tree to compare
   * @return this.equals(that)?
   */
  def compare(that: DNode): Boolean = {
    lazy val ifd1 = this.ifdefy()
    lazy val ifd2 = that.ifdefy()
    
    def chop(s: String): String = s.split('\n').map(_.trim.filter(_ >= ' ')).mkString.replace(" ", "")
    
    if (ifd1.size == ifd2.size) {
      (ifd1 zip ifd2).map { 
        case (x, y) => chop(x) equals chop(y) 
      }.foldLeft(true)(_ && _)
    } else {
      false
    }
  }

}

/**
 * Utility methods in order to create a DModel from given output.
 */
object DNode {

  /**
   * Creates a DModel tree from output provided by the OakInterpreter.
   *
   * TODO The OakInterpreter should generate the output directly.
   *
   * @param v OakValue to create a DModel tree of. This is typically
   * a OakValueSequence.
   * @return DNode root of the DModel tree
   */
  @deprecated def createDNode(v: OakValue): DNode = v match {
    case c: Choice => {
      SelectNode(c.getConstraint(), createDNode(c.v1), createDNode(c.v2))
    }
    case s: SymbolValue => {
      SymbolNode(s)
    }
    case o: OakValueSequence => {
      val nodes = o.getSequence.map { v => createDNode(v) }.asInstanceOf[List[DNode]]
      ConcatNode(nodes)
    }
    case s: StringValue => {
      assert(s.loc._1 != null) //FIXME
      LiteralNode(s)
    }
    case _ => {
      LiteralNode(v)
    }
  }

  /**
   * Creates a DModel tree from output provided by the OakInterpreter.
   *
   * TODO The OakInterpreter should generate the output directly.
   *
   * @param vs OakValues to create a DModel tree of.
   * @return DNode root of the DModel tree
   */
  @deprecated def createDNode(vs: Seq[OakValue]): DNode = {
    val nodes = vs.map { v => createDNode(v) }.asInstanceOf[List[DNode]]
    ConcatNode(nodes)
  }

  /**
   * Extracts all string literals as StringValues from
   * the DModel tree.
   *
   * @param node DModel tree root node
   * @return List of StringValues, which are literals in the DModel
   */
  def extractStringLiterals(node: DNode): Set[StringValue] = {

    // utility method for DModel tree traversal
    def extractStringLiterals(node: DNode, literals: List[StringValue]): List[StringValue] = node match {
      case c: SelectNode => literals ++ extractStringLiterals(c.v1, literals) ++ extractStringLiterals(c.v2, literals)
      case s: ConcatNode => {
        var lits = List[StringValue]()
        s.getChildren.foreach {
          c => lits ++= extractStringLiterals(c, List[StringValue]())
        }
        literals ++ lits
      }
      case l: LiteralNode => l.lv match {
        case s: StringValue => {
         
          assert(s.loc._1 != null) //FIXME
          literals ++ List(s)
        }
        case _ => List[StringValue]()
      }
      case _ => List[StringValue]()
    }

    // apply utility method
    return extractStringLiterals(node, List[StringValue]()).toSet
  }
  
  def createOutputGraph(node: DNode): scala.xml.Elem = {
    val listener = new OutputGraphListener("Output Graph")
    node.traverse(listener)
    listener.toXml
  }

}