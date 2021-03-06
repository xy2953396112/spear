package spear.expressions

import scala.util.{Failure, Try}

import spear.{Name, Row}
import spear.exceptions._
import spear.expressions.typecheck.TypeConstraint
import spear.trees.TreeNode
import spear.types.DataType
import spear.utils._

/**
 * A trait for expressions. Typically, concrete expression classes are immutable. One exception is
 * impure expressions (e.g., [[Rand]]), which are non-deterministic and contain mutable states.
 */
trait Expression extends TreeNode[Expression] {
  override def nodeName: String = getClass.getSimpleName.toLowerCase stripSuffix "$"

  override def toString: String = sqlLike

  /**
   * Whether the result of this [[Expression]] can be null when evaluated. False positive is allowed
   * ([[isNullable]] is true, while this expression never returns null), while false negative is not
   * allowed.
   */
  def isNullable: Boolean = children exists { _.isNullable }

  def referenceSet: Set[Attribute] = references.toSet

  def references: Seq[Attribute] = children.flatMap { _.references }.distinct

  /**
   * Returns the data type of this [[Expression]] if it's well-typed, or throws
   * [[spear.exceptions.AnalysisException AnalysisException]] (or one of its subclasses) if it's
   * not.
   *
   * By default, this method performs type checking and delegates to [[strictDataType]] if this
   * [[Expression]] is well-typed. In general, concrete expression classes should override
   * [[Expression.strictlyTyped]] instead of this method with the exception when the concrete
   * expression always have a fixed data type (e.g. predicates always return boolean values).
   *
   * @see [[strictDataType]]
   * @see [[strictlyTyped]]
   */
  def dataType: DataType = whenWellTyped(strictlyTyped.strictDataType)

  def evaluate(input: Row): Any

  def evaluated: Any = evaluate(null)

  def debugString: String = template(children map { _.debugString })

  def sql: Try[String] = trySequence(children map { _.sql }) map template

  def sqlLike: String = template(children map { e => e.sql getOrElse e.debugString })

  /**
   * Whether this expression can be folded (evaluated) into a single [[Literal]] value at compile
   * (planning) time. Foldable expressions can be optimized out when being compiled. For example
   * {{{
   *   Plus(Literal(1: Int), Literal(1: Int))
   * }}}
   * is foldable, while
   * {{{
   *   Plus(Literal(1: Int), column: AttributeRef)
   * }}}
   * is not.
   */
  lazy val isFoldable: Boolean = children forall { _.isFoldable }

  /**
   * Whether this [[Expression]] is pure. A pure [[Expression]] is deterministic, and always
   * returns the same value when given the same input. Typical examples of impure [[Expression]]s
   * include [[Rand]].
   */
  lazy val isPure: Boolean = children forall { _.isPure }

  /**
   * Whether this [[Expression]] is resolved. An [[Expression]] is resolved if and only if it
   * satisfies the following two conditions:
   *
   *  1. It's not any of the following [[Expression]]:
   *     - [[UnresolvedAttribute]]
   *     - [[UnresolvedFunction]]
   *     - [[Star]]
   *     - [[UnresolvedAlias]]
   *  2. All of its child [[Expression]]s are resolved.
   */
  lazy val isResolved: Boolean = children forall { _.isResolved }

  /**
   * Whether this [[Expression]] is bound. A bound [[Expression]] is [[isResolved resolved]] and
   * doesn't contain any [[Attribute]]s.
   */
  lazy val isBound: Boolean = isResolved && children.forall { _.isBound }

  /**
   * Returns a strictly-typed copy of this [[Expression]]. In most cases, concrete expression
   * classes only need to override [[Expression.typeConstraint]] by combining built-in
   * [[spear.expressions.typecheck.TypeConstraint type constraints]], since this lazy val simply
   * delegates to [[Expression.typeConstraint]] by default.
   *
   * To pass type checking, an [[Expression]] `e` must be either strictly-typed or well-typed:
   *
   *  1. Strictly-typed: `e` is strictly-typed iff
   *
   *     - `e` is resolved, and
   *     - all child expressions of `e` are strictly-typed, and
   *     - all child expressions of `e` immediately meet type constraint of `e`.
   *
   *  2. Well-typed: `e` is well-typed iff
   *
   *     - `e` is resolved, and
   *     - all child expressions of `e` are well-typed, and
   *     - all child expressions of `e` can meet type constraint of `e` by applying at most one
   *       implicit cast for each child expression.
   *
   * For example, say attribute `a` is an attribute of type `BIGINT`, then `a + 1` is well-typed
   * because:
   *
   *  - Operator `+` requires both branches share the same type, while
   *  - literal `1` is of type `INT`, but can be implicitly casted to `BIGINT`.
   *
   * On the other hand, `a + CAST(1 AS BIGINT)` is strictly-typed because both branches are of type
   * `BIGINT`.
   *
   * @see [[typeConstraint]]
   */
  lazy val strictlyTyped: Expression = {
    val newChildren = typeConstraint.enforced
    val changed = (newChildren, children).zipped forall { _ same _ }
    if (changed) this else withChildren(newChildren)
  }

  /**
   * Indicates whether this [[Expression]] is strictly-typed.
   *
   * @see [[strictlyTyped]]
   */
  lazy val isStrictlyTyped: Boolean = isWellTyped && (strictlyTyped same this)

  /**
   * Indicates whether this [[Expression]] is well-typed.
   *
   * @see [[strictlyTyped]]
   */
  lazy val isWellTyped: Boolean = isResolved && Try(strictlyTyped).isSuccess

  /**
   * Type constraint for all input expressions.
   */
  protected def typeConstraint: TypeConstraint = children.anyType

  /**
   * Returns `value` if this [[Expression]] is strictly-typed, otherwise throws a
   * [[spear.exceptions.TypeCheckException TypeCheckException]].
   *
   * @see [[strictlyTyped]]
   */
  @throws[TypeCheckException]("If this expression is not strictly-typed")
  protected def whenStrictlyTyped[T](value: => T): T =
    if (isStrictlyTyped) value else throw new TypeCheckException(this)

  /**
   * Returns `value` if this [[Expression]] is well-typed.
   *
   * @see [[strictlyTyped]]
   */
  @throws[TypeCheckException]("If this expression is not well-typed")
  protected def whenWellTyped[T](value: => T): T =
    if (isWellTyped) value else throw new TypeCheckException(this, Try(strictlyTyped).failed.get)

  protected def whenBound[T](value: => T): T =
    if (isBound) value else throw new ExpressionNotBoundException(this)

  /**
   * A template method for building `debugString` and `sql`.
   */
  protected def template(childList: Seq[String]): String =
    childList mkString (s"$nodeName(", ", ", ")")

  /**
   * Returns the data type of this [[Expression]]. Different from [[Expression.dataType]], this
   * method is only called when this [[Expression]] is strictly-typed.
   *
   * @see [[strictlyTyped]]
   */
  protected lazy val strictDataType: DataType = throw new ContractBrokenException(
    s"${getClass.getName} must override either dataType or strictDataType."
  )
}

object Expression {
  implicit class ResolutionUtils(expression: Expression) {
    /**
     * Tries to resolve this [[Expression]] using a given list of `input` [[NamedExpression]]s. This
     * method doesn't throw any exception if this [[Expression]] can't be fully resolved.
     */
    def tryResolveUsing(input: Seq[NamedExpression]): Expression =
      resolve(expression, input, errorIfNotFound = false)

    private def resolve(
      expression: Expression, input: Seq[NamedExpression], errorIfNotFound: Boolean
    ): Expression = expression.transformDown {
      case unresolved @ UnresolvedAttribute(name, qualifier) =>
        val candidates = input.map { _.attr }.distinct.collect {
          case a: AttributeRef if a.name == name && qualifier == a.qualifier => a
          case a: AttributeRef if a.name == name && qualifier.isEmpty        => a
        }

        candidates match {
          case Seq(attribute) =>
            attribute

          case Nil =>
            Option(unresolved) filterNot { _ => errorIfNotFound } getOrElse {
              throw new ResolutionFailureException(
                s"Failed to resolve attribute $unresolved using ${input mkString ("[", ", ", "]")}"
              )
            }

          case _ =>
            throw new ResolutionFailureException(
              s"""Multiple ambiguous input attributes found while resolving $unresolved using
                 |${input mkString ("[", ", ", "]")}
                 |""".oneLine
            )
        }
    }

    def unaliasUsing(projectList: Seq[NamedExpression]): Expression = unaliasUsing(projectList, "")

    def unaliasUsing(
      projectList: Seq[NamedExpression],
      firstNamespace: String,
      otherNamespaces: String*
    ): Expression = {
      val namespaces = firstNamespace +: otherNamespaces
      val rewrite = projectList.collect {
        case a: InternalAlias if namespaces contains a.namespace => a.expressionID -> a.child
        case a: Alias if namespaces contains a.namespace         => a.expressionID -> a.child
      }.toMap

      expression transformUp {
        case a: ResolvedAttribute if rewrite contains a.expressionID =>
          rewrite(a.expressionID)
      }
    }

    def bindTo(input: Seq[Attribute]): Expression = expression transformUp {
      case ref: ResolvedAttribute =>
        val ordinal = input.indexWhere(_.expressionID == ref.expressionID)

        if (ordinal == -1) {
          val inputAttributes = input.map(_.caption).mkString(", ")
          throw new ResolutionFailureException(
            s"Failed to bind attribute reference $ref to any input attributes: $inputAttributes"
          )
        } else {
          BoundRef(ordinal, ref.dataType, ref.isNullable)
        }
    }
  }
}

trait StatefulExpression[State] extends Expression {
  private var state: Option[State] = None

  override lazy val isPure: Boolean = false

  override lazy val isFoldable: Boolean = false

  override protected def makeCopy(args: Seq[AnyRef]): Expression = {
    state.foreach { currentState =>
      throw new ContractBrokenException(
        s"Stateful expression $this with state $currentState cannot be copied after initialization"
      )
    }
    super.makeCopy(args)
  }

  protected def initialState: State

  protected def statefulEvaluate(state: State, input: Row): (Any, State)

  override def evaluate(input: Row): Any = {
    if (state.isEmpty) {
      state = Some(initialState)
    }

    val (result, newState) = statefulEvaluate(state.get, input)
    state = Some(newState)

    result
  }
}

trait NonSQLExpression extends Expression {
  override def sql: Try[String] = Failure(new UnsupportedOperationException(
    s"Expression $debugString doesn't have a SQL representation"
  ))

  override def sqlLike: String = debugString
}

trait LeafExpression extends Expression {
  override def children: Seq[Expression] = Seq.empty

  override def caption: String = debugString

  override def sqlLike: String = sql getOrElse debugString
}

trait UnaryExpression extends Expression {
  def child: Expression

  override def children: Seq[Expression] = Seq(child)

  protected def nullSafeEvaluate(value: Any): Any =
    throw new ContractBrokenException(
      s"${getClass.getName} must override either evaluate or nullSafeEvaluate"
    )

  override def evaluate(input: Row): Any = {
    Option(child.evaluate(input)).map { nullSafeEvaluate }.orNull
  }

  override protected def template(childList: Seq[String]): String = template(childList.head)

  protected def template(childString: String): String = super.template(childString :: Nil)
}

trait BinaryExpression extends Expression {
  def left: Expression

  def right: Expression

  override def children: Seq[Expression] = Seq(left, right)

  def nullSafeEvaluate(lhs: Any, rhs: Any): Any =
    throw new ContractBrokenException(
      s"${getClass.getName} must override either evaluate or nullSafeEvaluate"
    )

  override def evaluate(input: Row): Any = {
    val maybeResult = for {
      lhs <- Option(left evaluate input)
      rhs <- Option(right evaluate input)
    } yield nullSafeEvaluate(lhs, rhs)

    maybeResult.orNull
  }
}

trait Operator { this: Expression =>
  def operator: String

  override def nodeName: String = operator
}

trait BinaryOperator extends BinaryExpression with Operator {
  override protected def template(childrenList: Seq[String]): String =
    childrenList mkString ("(", s" $operator ", ")")
}

trait UnaryOperator extends UnaryExpression with Operator {
  override lazy val isNullable: Boolean = child.isNullable

  override protected def template(childString: String): String = s"($operator$childString)"
}

trait UnevaluableExpression extends Expression {
  override lazy val isFoldable: Boolean = false

  override def evaluate(input: Row): Any = throw new ExpressionUnevaluableException(this)
}

trait UnresolvedExpression extends Expression with UnevaluableExpression with NonSQLExpression {
  override lazy val dataType: DataType = throw new ExpressionUnresolvedException(this)

  override lazy val isNullable: Boolean = throw new ExpressionUnresolvedException(this)

  override lazy val strictlyTyped: Expression = throw new ExpressionUnresolvedException(this)

  override lazy val isResolved: Boolean = false
}

case class UnresolvedFunction(name: Name, args: Seq[Expression], isDistinct: Boolean)
  extends UnresolvedExpression {

  override def children: Seq[Expression] = args

  override def template(childList: Seq[String]): String = {
    val distinctString = if (isDistinct) "DISTINCT " else ""
    s"?$name?($distinctString${childList mkString ", "})"
  }

  def distinct: UnresolvedFunction = if (isDistinct) this else copy(isDistinct = true)
}
