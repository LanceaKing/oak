package edu.cmu.cs.oak.analysis

import java.nio.file.Path
import java.nio.file.Paths
import java.util.LinkedHashMap

import scala.collection.JavaConversions._
import scala.collection.mutable.HashSet

import org.slf4j.LoggerFactory

import com.caucho.quercus.Location
import com.caucho.quercus.expr._
import com.caucho.quercus.function.AbstractFunction
import com.caucho.quercus.program.Function
import com.caucho.quercus.program.InterpretedClassDef
import com.caucho.quercus.program.QuercusProgram
import com.caucho.quercus.statement._

import edu.cmu.cs.oak.core.OakEngine
import edu.cmu.cs.oak.value.StringValue

/**
 * Traverses the PHP AST provided by Quercus and retrieves all
 * string literals, regardless of reachability. Besides, additoinal
 * information about all string literals, such as its location
 * and its context (surrounding expression) are collected.
 *
 * @author Stefan Muehlbauer <smuhlbau@andrew.cmu.edu>
 */
class ASTVisitor(path: Path) {

  /** Set of found string literals including contextual information. */
  lazy val stringLiterals = new HashSet[StringValue]

  /** Engine used to access PHP scripts. */
  lazy val engine = new OakEngine()

  /** Logger instance. */
  lazy val logger = LoggerFactory.getLogger(classOf[ASTVisitor])

  /**
   * Set the global parent path for the ASTVisitor
   */
  def init() {
    ASTVisitor.rootPath = path.getParent()
  }
  
  /**
   * Loads a program from file and retrieves string literals.
   *
   * @param path URL to the PHP source file to parse
   */
  def retrieveStringLiterals(): Set[StringValue] = {
    val program = engine.loadFromFile(path)

    // Traverse function string literals
    List(program.getFunctionList.toArray: _*).foreach {
      f => visit(f.asInstanceOf[Function]._statement)
    }
    visit(program.getStatement)
    stringLiterals.toSet
  }

  def visit(stmt: Statement): Unit = stmt match {

    /**
     * Case for AST node class BlockStatement.
     */
    case s: BlockStatement => {

      s._statements.foreach {
        st => visit(st)
      }

    }

    /**
     * Case for AST node class BreakStatement.
     */
    case s: BreakStatement => {}

    /**
     * Case for AST node class ClassDefStatement.
     */
    case s: ClassDefStatement => {
      val ic = s._cl
      val functionMap = ic._functionMap//Interpreter.accessField(ic, "_functionMap").asInstanceOf[LinkedHashMap[com.caucho.quercus.env.StringValue, AbstractFunction]]

      val functions = functionMap.map { case (k, v) => (k.toString(), v) }
      functions.values.foreach {
        f => visit(f.asInstanceOf[Function]._statement)
      }
    }

    /**
     * Case for AST node class ClassStaticStatement.
     */
    case s: ClassStaticStatement => ???

    /**
     * Case for AST node class ClosureStaticStatement.
     */
    case s: ClosureStaticStatement => ???

    /**
     * Case for AST node class ContinueStatement.
     */
    case s: ContinueStatement => {}

    /**
     * Case for AST node class DoStatement.
     */
    case s: DoStatement => ???

    /**
     * Case for AST node class EchoStatement.
     */
    case s: EchoStatement => {

      val expr = s._expr
      visit(expr, ASTVisitor.getStatementLineNr(s))
    }

    /**
     * Case for AST node class ExprStatement.
     */
    case s: ExprStatement => {
      val expr = s._expr
      
      if (expr.isInstanceOf[FunIncludeExpr] || expr.isInstanceOf[FunIncludeOnceExpr]) {
        val path = Paths.get(this.path.getParent.toString + "/" + expr.toString.replace("\"", ""))
        val includeVisitor = new ASTVisitor(path)
        stringLiterals ++= includeVisitor.retrieveStringLiterals()
      } else {
        visit(expr, ASTVisitor.getStatementLineNr(s))
      }
      
    }

    /**
     * Case for AST node class ForeachStatement.
     */
    case s: ForeachStatement => ???

    /**
     * Case for AST node class ForStatement.
     */
    case s: ForStatement => {
      val block = s._block
      visit(block)
    }

    /**
     * Case for AST node class FunctionDefStatement.
     */
    case s: FunctionDefStatement => ???

    /**
     * Case for AST node class GlobalStatement.
     */
    case s: GlobalStatement => ???

    /**
     * Case for AST node class IfStatement.
     */
    case s: IfStatement => {
      //val expr = Interpreter.accessField(s, "_test").asInstanceOf[Expr]
      //visit(expr, ASTVisitor.getStatementLineNr(s))

      visit(s._trueBlock)
      try {
        visit(s._falseBlock)
      } catch {
        case e: Exception => {}
      }
    }

    /**
     * Case for AST node class NullStatement.
     */
    case s: NullStatement => ???

    /**
     * Case for AST node class ReturnRefStatement.
     */
    case s: ReturnRefStatement => { /* Nothing to implement here */ }

    /**
     * Case for AST node class ReturnStatement.
     */
    case s: ReturnStatement => {
      val expr = s._expr
      visit(expr, ASTVisitor.getStatementLineNr(s))
    }

    /**
     * Case for AST node class StaticStatement.
     */
    case s: StaticStatement => ???

    /**
     * Case for AST node class SwitchStatement.
     */
    case s: SwitchStatement => {
      val blocks = s._blocks
      val default = s._defaultBlock
      blocks.foreach { b => visit(b) }
      if (default != null) {
        visit(default)
      }
    }

    /**
     * Case for AST node class TextStatement.
     */
    case s: TextStatement => {

      val loc = s._location

      val value = s._value.toString()
    }

    /**
     * Case for AST node class ThrowStatement.
     */
    case s: ThrowStatement => ???

    /**
     * Case for AST node class TryStatement.
     */
    case s: TryStatement => ???

    /**
     * Case for AST node class VarGlobalStatement.
     */
    case s: VarGlobalStatement => ???

    /**
     * Case for AST node class WhileStatement.
     */
    case s: WhileStatement => {
      val expr = s._test
      visit(expr, ASTVisitor.getStatementLineNr(s))

      visit(s._block)
    }
  }

  /**
   *
   */
  def visit(expr: Expr, loc: Int): Unit = expr match {

    /**
     * Case for AST node class AbstractBinaryExpr.
     */
    case e: AbstractBinaryExpr => {
      visit(e._left, loc)
      visit(e._right, loc)
    }

    /**
     * Case for AST node class AbstractLongValuedExpr.
     */
    case e: AbstractLongValuedExpr => ???

    /**
     * Case for AST node class AbstractMethodExpr.
     */
    case e: AbstractMethodExpr => {}

    /**
     * Case for AST node class AbstractUnaryExpr.
     */
    case e: AbstractUnaryExpr => {}

    /**
     * Case for AST node class AbstractVarExpr.
     */
    case e: AbstractVarExpr => {}

    /**
     * Case for AST node class ArrayIsSetExpr.
     */
    case e: ArrayIsSetExpr => ???

    /**
     * Case for AST node class ArrayTailExpr.
     */
    case e: ArrayTailExpr => ???

    /**
     * Case for AST node class ArrayUnsetExpr.
     */
    case e: ArrayUnsetExpr => ???

    /**
     * Case for AST node class BinaryAddExpr.
     */
    case e: BinaryAddExpr => ???

    /**
     * Case for AST node class BinaryAndExpr.
     */
    case e: BinaryAndExpr => ???

    /**
     * Case for AST node class BinaryAppendExpr.
     */
    case e: BinaryAppendExpr => {
      visit(e._value, loc);
      val next = e._next
      if (next != null) { visit(next, loc) }
    }

    /**
     * Case for AST node class BinaryAssignExpr.
     */
    case e: BinaryAssignExpr => {
      val _var = e._var
      val value = e._value
      visit(_var, loc)
      visit(value, loc)
    }

    /**
     * Case for AST node class BinaryAssignListEachExpr.
     */
    case e: BinaryAssignListEachExpr => ???

    /**
     * Case for AST node class BinaryAssignListExpr.
     */
    case e: BinaryAssignListExpr => {
      val value = e._value
      visit(value, loc)
    }

    /**
     * Case for AST node class BinaryAssignRefExpr.
     */
    case e: BinaryAssignRefExpr => {}

    /**
     * Case for AST node class BinaryBitAndExpr.
     */
    case e: BinaryBitAndExpr => ???

    /**
     * Case for AST node class BinaryBitOrExpr.
     */
    case e: BinaryBitOrExpr => ???

    /**
     * Case for AST node class BinaryBitXorExpr.
     */
    case e: BinaryBitXorExpr => ???

    /**
     * Case for AST node class BinaryCharAtExpr.
     */
    case e: BinaryCharAtExpr => ???

    /**
     * Case for AST node class BinaryCommaExpr.
     */
    case e: BinaryCommaExpr => ???

    /**
     * Case for AST node class BinaryDivExpr.
     */
    case e: BinaryDivExpr => ???

    /**
     * Case for AST node class BinaryEqExpr.
     */
    case e: BinaryEqExpr => ???

    /**
     * Case for AST node class BinaryEqualsExpr.
     */
    case e: BinaryEqualsExpr => ???

    /**
     * Case for AST node class BinaryGeqExpr.
     */
    case e: BinaryGeqExpr => ???

    /**
     * Case for AST node class BinaryGtExpr.
     */
    case e: BinaryGtExpr => ???

    /**
     * Case for AST node class BinaryInstanceOfExpr.
     */
    case e: BinaryInstanceOfExpr => ???

    /**
     * Case for AST node class BinaryInstanceOfVarExpr.
     */
    case e: BinaryInstanceOfVarExpr => ???

    /**
     * Case for AST node class BinaryLeftShiftExpr.
     */
    case e: BinaryLeftShiftExpr => ???

    /**
     * Case for AST node class BinaryLeqExpr.
     */
    case e: BinaryLeqExpr => ???

    /**
     * Case for AST node class BinaryLtExpr.
     */
    case e: BinaryLtExpr => ???

    /**
     * Case for AST node class BinaryModExpr.
     */
    case e: BinaryModExpr => ???

    /**
     * Case for AST node class BinaryMulExpr.
     */
    case e: BinaryMulExpr => ???

    /**
     * Case for AST node class BinaryNeqExpr.
     */
    case e: BinaryNeqExpr => ???

    /**
     * Case for AST node class BinaryOrExpr.
     */
    case e: BinaryOrExpr => ???

    /**
     * Case for AST node class BinaryRightShiftExpr.
     */
    case e: BinaryRightShiftExpr => ???

    /**
     * Case for AST node class BinarySubExpr.
     */
    case e: BinarySubExpr => ???

    /**
     * Case for AST node class BinaryXorExpr.
     */
    case e: BinaryXorExpr => ???

    /**
     * Case for AST node class CallExpr.
     */
    case e: CallExpr => {
      val args = e._args
      args.foreach { a => visit(a, loc) }
    }

    /**
     * Case for AST node class CallVarExpr.
     */
    case e: CallVarExpr => ???

    /**
     * Case for AST node class ClassConstExpr.
     */
    case e: ClassConstExpr => ???

    /**
     * Case for AST node class ClassConstructExpr.
     */
    case e: ClassConstructExpr => ???

    /**
     * Case for AST node class ClassConstructorExpr.
     */
    case e: ClassConstructorExpr => ???

    /**
     * Case for AST node class ClassFieldExpr.
     */
    case e: ClassFieldExpr => ???

    /**
     * Case for AST node class ClassFieldVarExpr.
     */
    case e: ClassFieldVarExpr => ???

    /**
     * Case for AST node class ClassMethodExpr.
     */
    case e: ClassMethodExpr => ???

    /**
     * Case for AST node class ClassMethodVarExpr.
     */
    case e: ClassMethodVarExpr => ???

    /**
     * Case for AST node class ClassVarConstExpr.
     */
    case e: ClassVarConstExpr => ???

    /**
     * Case for AST node class ClassVarFieldExpr.
     */
    case e: ClassVarFieldExpr => ???

    /**
     * Case for AST node class ClassVarFieldVarExpr.
     */
    case e: ClassVarFieldVarExpr => ???

    /**
     * Case for AST node class ClassVarMethodExpr.
     */
    case e: ClassVarMethodExpr => ???

    /**
     * Case for AST node class ClassVarMethodVarExpr.
     */
    case e: ClassVarMethodVarExpr => ???

    /**
     * Case for AST node class ClassVarNameConstExpr.
     */
    case e: ClassVarNameConstExpr => ???

    /**
     * Case for AST node class ClassVarNameVirtualConstExpr.
     */
    case e: ClassVarNameVirtualConstExpr => ???

    /**
     * Case for AST node class ClassVarVarConstExpr.
     */
    case e: ClassVarVarConstExpr => ???

    /**
     * Case for AST node class ClassVirtualConstExpr.
     */
    case e: ClassVirtualConstExpr => ???

    /**
     * Case for AST node class ClassVirtualFieldExpr.
     */
    case e: ClassVirtualFieldExpr => ???

    /**
     * Case for AST node class ClassVirtualFieldVarExpr.
     */
    case e: ClassVirtualFieldVarExpr => ???

    /**
     * Case for AST node class ClassVirtualMethodExpr.
     */
    case e: ClassVirtualMethodExpr => ???

    /**
     * Case for AST node class ClassVirtualMethodVarExpr.
     */
    case e: ClassVirtualMethodVarExpr => ???

    /**
     * Case for AST node class ClosureExpr.
     */
    case e: ClosureExpr => ???

    /**
     * Case for AST node class ConditionalExpr.
     */
    case e: ConditionalExpr => {
      visit(e._test, loc)
      visit(e._trueExpr, loc)
      visit(e._falseExpr, loc)
    }

    /**
     * Case for AST node class ConditionalShortExpr.
     */
    case e: ConditionalShortExpr => ???

    /**
     * Case for AST node class ConstClassExpr.
     */
    case e: ConstClassExpr => ???

    /**
     * Case for AST node class ConstDirExpr.
     */
    case e: ConstDirExpr => ???

    /**
     * Case for AST node class ConstExpr.
     */
    case e: ConstExpr => { }

    /**
     * Case for AST node class ConstFileExpr.
     */
    case e: ConstFileExpr => ???

    /**
     * Case for AST node class DieExpr.
     */
    case e: DieExpr => ???

    /**
     * Case for AST node class FunArrayExpr.
     */
    case e: FunArrayExpr => {
      val values = e._values
      values.foreach { v => visit(v, loc) }
    }

    /**
     * Case for AST node class FunCloneExpr.
     */
    case e: FunCloneExpr => ???

    /**
     * Case for AST node class FunDieExpr.
     */
    case e: FunDieExpr => {
      //visit(Interpreter.accessField(e, "_value").asInstanceOf[Expr], loc)
    }

    /**
     * Case for AST node class FunEachExpr.
     */
    case e: FunEachExpr => ???

    /**
     * Case for AST node class FunEmptyExpr.
     */
    case e: FunEmptyExpr => ???

    /**
     * Case for AST node class FunExitExpr.
     */
    case e: FunExitExpr => ???

    /**
     * Case for AST node class FunGetCalledClassExpr.
     */
    case e: FunGetCalledClassExpr => ???

    /**
     * Case for AST node class FunGetClassExpr.
     */
    case e: FunGetClassExpr => ???

    /**
     * Case for AST node class FunIncludeExpr.
     */
    case e: FunIncludeExpr => {
      println(e)
    }

    /**
     * Case for AST node class FunIncludeOnceExpr.
     */
    case e: FunIncludeOnceExpr => {
      println(e)    
    }

    /**
     * Case for AST node class FunIssetExpr.
     */
    case e: FunIssetExpr => ???

    /**
     * Case for AST node class ImportExpr.
     */
    case e: ImportExpr => ???

    /**
     * Case for AST node class ListHeadExpr.
     */
    case e: ListHeadExpr => ???

    /**
     * Case for AST node class LiteralBinaryStringExpr.
     */
    case e: LiteralBinaryStringExpr => ???

    /**
     * Case for AST node class LiteralExpr.
     */
    case e: LiteralExpr => {
      //logger.info("Found string literal " + e.toString)
      //this.stringLiterals.put(e.toString(), null)
    }

    /**
     * Case for AST node class LiteralLongExpr.
     */
    case e: LiteralLongExpr => ???

    /**
     * Case for AST node class LiteralNullExpr.
     */
    case e: LiteralNullExpr => {
      // nothing happens
    }

    /**
     * Case for AST node class LiteralStringExpr.
     */
    case e: LiteralStringExpr => {

      val string = StringValue(e._value.toString(), e._location)
      stringLiterals += string
    }

    /**
     * Case for AST node class LiteralUnicodeExpr.
     */
    case e: LiteralUnicodeExpr => {
      val string = StringValue(e._value.toString(), e._location)
      stringLiterals += string
    }

    /**
     * Case for AST node class ObjectFieldExpr.
     */
    case e: ObjectFieldExpr => ???

    /**
     * Case for AST node class ObjectFieldVarExpr.
     */
    case e: ObjectFieldVarExpr => ???

    /**
     * Case for AST node class ObjectMethodExpr.
     */
    case e: ObjectMethodExpr => ???

    /**
     * Case for AST node class ObjectMethodVarExpr.
     */
    case e: ObjectMethodVarExpr => ???

    /**
     * Case for AST node class ObjectNewExpr.
     */
    case e: ObjectNewExpr => {}

    /**
     * Case for AST node class ObjectNewStaticExpr.
     */
    case e: ObjectNewStaticExpr => ???

    /**
     * Case for AST node class ObjectNewVarExpr.
     */
    case e: ObjectNewVarExpr => ???

    /**
     * Case for AST node class ParamDefaultExpr.
     */
    case e: ParamDefaultExpr => ???

    /**
     * Case for AST node class ParamRequiredExpr.
     */
    case e: ParamRequiredExpr => ???

    /**
     * Case for AST node class ThisExpr.
     */
    case e: ThisExpr => ???

    /**
     * Case for AST node class ThisFieldExpr.
     */
    case e: ThisFieldExpr => ???

    /**
     * Case for AST node class ThisFieldVarExpr.
     */
    case e: ThisFieldVarExpr => ???

    /**
     * Case for AST node class ThisMethodExpr.
     */
    case e: ThisMethodExpr => ???

    /**
     * Case for AST node class ThisMethodVarExpr.
     */
    case e: ThisMethodVarExpr => ???

    /**
     * Case for AST node class ToArrayExpr.
     */
    case e: ToArrayExpr => ???

    /**
     * Case for AST node class ToBinaryExpr.
     */
    case e: ToBinaryExpr => ???

    /**
     * Case for AST node class ToBooleanExpr.
     */
    case e: ToBooleanExpr => ???

    /**
     * Case for AST node class ToDoubleExpr.
     */
    case e: ToDoubleExpr => ???

    /**
     * Case for AST node class ToLongExpr.
     */
    case e: ToLongExpr => ???

    /**
     * Case for AST node class ToObjectExpr.
     */
    case e: ToObjectExpr => ???

    /**
     * Case for AST node class ToStringExpr.
     */
    case e: ToStringExpr => ???

    /**
     * Case for AST node class ToUnicodeExpr.
     */
    case e: ToUnicodeExpr => ???

    /**
     * Case for AST node class TraitParentClassConstExpr.
     */
    case e: TraitParentClassConstExpr => ???

    /**
     * Case for AST node class TraitParentClassMethodExpr.
     */
    case e: TraitParentClassMethodExpr => ???

    /**
     * Case for AST node class UnaryBitNotExpr.
     */
    case e: UnaryBitNotExpr => ???

    /**
     * Case for AST node class UnaryCopyExpr.
     */
    case e: UnaryCopyExpr => ???

    /**
     * Case for AST node class UnaryMinusExpr.
     */
    case e: UnaryMinusExpr => ???

    /**
     * Case for AST node class UnaryNotExpr.
     */
    case e: UnaryNotExpr => ???

    /**
     * Case for AST node class UnaryPlusExpr.
     */
    case e: UnaryPlusExpr => ???

    /**
     * Case for AST node class UnaryPostIncrementExpr.
     */
    case e: UnaryPostIncrementExpr => ???

    /**
     * Case for AST node class UnaryPreIncrementExpr.
     */
    case e: UnaryPreIncrementExpr => ???

    /**
     * Case for AST node class UnaryRefExpr.
     */
    case e: UnaryRefExpr => ???

    /**
     * Case for AST node class UnarySuppressErrorExpr.
     */
    case e: UnarySuppressErrorExpr => ???

    /**
     * Case for AST node class UnaryUnsetExpr.
     */
    case e: UnaryUnsetExpr => ???

    /**
     * Case for AST node class VarExpr.
     */
    case e: VarExpr => ???

    /**
     * Case for AST node class VarState.
     */
    //case e: VarState => ???

    /**
     * Case for AST node class VarTempExpr.
     */
    case e: VarTempExpr => ???

    /**
     * Case for AST node class VarUnsetExpr.
     */
    case e: VarUnsetExpr => {}

    /**
     * Case for AST node class VarVarExpr.
     */
    case e: VarVarExpr => ???

  }

}

object ASTVisitor {
  
  var rootPath: Path = null

  /**
   * Auxiliary method to retrieve the line nr of a given
   * statement AST node.
   *
   * @param Statement statement AST node to inspect
   *
   * @return line nr
   */
  def getStatementLineNr(stmt: Statement): Int = {
    val location = try {
      stmt._location
    } catch {
      case e: Exception => throw new RuntimeException(e)
    }
    return location.getLineNumber
  }
}
