package edu.cmu.cs.oak.lib.array

import java.nio.file.Path

import com.caucho.quercus.expr.Expr

import edu.cmu.cs.oak.lib.InterpreterPluginProvider
import edu.cmu.cs.oak.core.OakInterpreter
import edu.cmu.cs.oak.env.Environment
import edu.cmu.cs.oak.env.OakHeap
import edu.cmu.cs.oak.lib.InterpreterPlugin
import edu.cmu.cs.oak.value.ArrayValue
import edu.cmu.cs.oak.value.IntValue
import edu.cmu.cs.oak.value.OakValue
import edu.cmu.cs.oak.value.SymbolValue
import com.caucho.quercus.Location

class Count extends InterpreterPlugin {
  
  override def getName(): String = "count"
  
  override def visit(provider: InterpreterPluginProvider, args: List[OakValue], loc: Location, env: Environment): OakValue = {
    
    /* Assert that the function has only 
     * been called with exactly one argument. */
    assert(args.size == 1)
    
    val res = args(0)
    res match {
      case a: ArrayValue => {
        return IntValue(a.getSize)
      }
      case _ =>  return SymbolValue(args(0).toString, OakHeap.getIndex(), null)
    }
    
  }
  
}