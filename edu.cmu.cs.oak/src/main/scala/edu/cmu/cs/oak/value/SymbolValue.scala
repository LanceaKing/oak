package edu.cmu.cs.oak.value

import com.caucho.quercus.expr.Expr
import com.caucho.quercus.Location
import edu.cmu.cs.oak.core.SymbolFlag
import edu.cmu.cs.oak.core.OakInterpreter

/**
  *
  */
case class SymbolValue(e: String, id: Long, flag: SymbolFlag.Value) extends SymbolicValue {
  
  OakInterpreter.symbolSet += this
  
}
