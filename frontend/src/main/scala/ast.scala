

package de.cfaed.kitten

import scala.annotation.tailrec
import scala.util.matching.Regex
import scala.util.parsing.combinator.{PackratParsers, Parsers, RegexParsers}
import scala.util.parsing.input.{NoPosition, Position, Reader}


package ast {

  import tokens.*
  import types.{KDataType, KFun, KPrimitive, StackType}

  /**
    * @author Clément Fournier &lt;clement.fournier@tu-dresden.de&gt;
    */
  sealed trait KNode

  sealed trait KExpr extends KNode {
    override def toString: String = this match
      case Chain(a, b) => s"($a $b)"
      case FunApply(name) => if Character.isAlphabetic(name(0)) then name else s"($name)"
      case PushPrim(_, value) => value.toString
      case NameTopN(names) => names.mkString("-> ", ", ", ";")
      case PushList(items) => items.mkString("[", ", ", "]")
      case Quote(term) => s"{ $term }"
  }

  case class Chain(a: KExpr, b: KExpr) extends KExpr
  case class PushPrim[T](ty: KPrimitive[T], value: T) extends KExpr
  case class PushList(items: List[KExpr]) extends KExpr
  object PushPrim {
    val PushTrue: PushPrim[Boolean] = PushPrim(types.KBool, true)
    val PushFalse: PushPrim[Boolean] = PushPrim(types.KBool, false)
  }
  case class FunApply(name: String) extends KExpr
  case class Quote(term: KExpr) extends KExpr
  case class NameTopN(names: List[String]) extends KExpr {
    def stackType: StackType = StackType.generic(newTVar => {
      StackType(consumes = names.map(_ => newTVar()))
    })
  }


  object KittenParser extends Parsers with PackratParsers {
    override type Elem = KToken

    private def thunk =
      LBRACE ~> (exprSeq | accept("operator", { case OP(n) => FunApply(n) })) <~ RBRACE ^^ Quote

    private def parexpr = LPAREN ~> exprSeq <~ RPAREN

    private def primary: Parser[KExpr] =
      accept("boolean", { case TRUE => PushPrim.PushTrue })
        | accept("boolean", { case FALSE => PushPrim.PushFalse })
        | accept("identifier", { case ID(name) => FunApply(name) })
        | accept("number", { case NUMBER(v) => PushPrim(types.KInt, v) })
        | accept("string", { case STRING(v) => PushPrim(types.KString, v) })
        | (LBRACKET ~> repsep(exprSeq, COMMA) <~ RBRACKET ^^ PushList)
        | (LPAREN ~> (exprSeq | accept("operator", { case OP(n) => FunApply(n) })) <~ RPAREN)
        | thunk
        | (ARROW ~> rep1sep(accept("identifier", { case ID(name) => name }), COMMA) <~ SEMI ^^ NameTopN)
        | ((IF ~> parexpr.? ~ thunk
        ~ rep(ELIF ~> parexpr ~ thunk)
        ~ (ELSE ~> thunk)
        ) ^^ {
        case (cond: Option[KExpr]) ~ (thenThunk: Quote) ~ (elifs: List[KExpr ~ Quote]) ~ (elseThunk: Quote) =>
          def makeIf(thenThunk: Quote, elseThunk: Quote): KExpr =
            Chain(Chain(thenThunk, elseThunk), FunApply(eval.Env.Intrinsic_if))

          val foldedElseIf = elifs.foldRight[Quote](elseThunk) {
            case (c ~ t, f) => Quote(Chain(c, makeIf(t, f)))
          }
          val ifelse = makeIf(thenThunk, foldedElseIf)

          cond.map(c => Chain(c, ifelse)).getOrElse(ifelse)
      })

    private def unary: Parser[KExpr] =
      (OP("-") | OP("+") | OP("~")).? ~ primary ^^ {
        case Some(OP(op)) ~ e => Chain(e, FunApply("unary_" + op))
        case None ~ (e1: KExpr) => e1
      }

    private def multexpr: Parser[KExpr] =
      unary ~ rep((OP("*") | OP("/") | OP("%")) ~ unary) ^^ {
        case (e1: KExpr) ~ (list: List[(OP, KExpr)]) =>
          list.foldLeft(e1)((a: KExpr, b: (OP, KExpr)) => (a, b) match {
            case (a, (OP(op), b: KExpr)) => Chain(Chain(a, b), FunApply(op))
          })
      }

    private def addexpr: Parser[KExpr] =
      multexpr ~ rep((OP("+") | OP("-")) ~ multexpr) ^^ {
        case (e1: KExpr) ~ (list: List[OP ~ KExpr]) =>
          list.foldLeft(e1)((a, b) => (a, b) match {
            case (a, OP(op) ~ b) => Chain(Chain(a, b), FunApply(op))
          })
      }


    private def sequenceableExpr: Parser[KExpr] =
      addexpr

    private def exprSeq: Parser[KExpr] =
      rep1(sequenceableExpr) ^^ (_ reduceLeft Chain.apply)

    def apply(source: String): Either[KittenParseError, KExpr] = {
      val reader = new KTokenScanner(source)
      exprSeq(reader) match {
        case NoSuccess(msg, _) => Left(KittenParseError(msg))
        case Success(result, input) =>
          if (input.atEnd) validate(result)
          else Left(KittenParseError(s"Unparsed tokens: ${source.substring(math.max(0, input.pos.column - 1))}"))
      }
    }

    private def validate(e: KExpr): Either[KittenParseError, KExpr] = {
      e match
        case Chain(a, b) =>
          for {
            a1 <- validate(a)
            b1 <- validate(b)
          } yield Chain(a1, b1)
        case Quote(term) => validate(term).map(Quote)
        case node@NameTopN(names) =>
          if names.distinct.lengthCompare(names) != 0 then
            Left(KittenParseError.namesShouldBeUnique(node))
          else
            Right(node)
        case e => Right(e)

    }
  }


  case class ParsedCode(source: String, ast: KExpr)

  def parse(code: String): Either[KittenCompilationError, ParsedCode] = {
    KittenParser(code).map(e => ParsedCode(code, e))
  }

}
