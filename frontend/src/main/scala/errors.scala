package de.cfaed.kitten

import ast.KExpr
import eval.{Env, KValue}
import types.*


trait KittenError
trait KittenCompilationError extends KittenError

case class KittenParseError(msg: String) extends KittenCompilationError

case class KittenLexerError(msg: String) extends KittenCompilationError


case class KittenTypeError(msg: String) extends KittenCompilationError {
  var location: KExpr = _
}

object KittenTypeError {
  def mismatch(t1: StackType, t2: StackType): KittenTypeError = KittenTypeError(
    s"Mismatched types: $t1 is not compatible with $t2"
  )

  def cannotApply(e: KExpr,
                  t1: StackType,
                  t2: StackType,
                  a: KDataType,
                  b: KDataType): KittenTypeError = KittenTypeError(
    s"Mismatched types: $t1 is not compatible with $t2"
  )
}


case class KittenEvalError(msg: String) extends KittenError
object KittenEvalError {
  def undef(name: String): KittenEvalError = KittenEvalError(s"Undefined name $name")
  def stackTypeError(t: StackType, env:Env): KittenEvalError =
    KittenEvalError(s"Cannot evaluate function of type $t with stack ${env.stackToString}")
}