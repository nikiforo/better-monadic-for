package com.olegpy.bm4

import scala.reflect.internal.util.FreshNameCreator


trait NoTupleBinding extends TreeUtils {
  import global._
  def noTupling: Boolean

  object NoTupleBinding {
    def unapply(arg: Tree): Option[Tree] = arg match {
      case _ if !noTupling => None
      case TupleBinding(Tupled(main, method, param, vals, used, result)) =>
        val usedVal: Set[String] = used.collect {
          case Bind(TermName(str), _) => str
        }.toSet
        val noUnusedVals = vals.mapConserve {
          // Synthetic vals are generated by destructuring. We don't want to touch them
          case v @ ValDef(mods, _, _, _) if mods.hasFlag(Flag.SYNTHETIC) => v

          // we wrap RHS in synthetic val ourselves because we cannot check if it's pure or not without typer
          // (pure exprs will be generated by destructuring) but we don't want warnings to be issued on
          // expressions like (_, x) = tmp because a temp variable underscore is written to is unused
          case ValDef(_, TermName(s), tp, rhs) if !usedVal(s) =>
            val name = freshTermName("$suppressedUnused$")(currentFreshNameCreator)
            val defn = ValDef(Modifiers() | Flag.SYNTHETIC, name, tp, rhs)
            replaceTree(rhs, defn)

          case a => a
        }

        // Retrofit synthetic/artifact flags for people using -Xwarn-unused:params
        val param2 =
          if (param.name.containsChar('$')) param.copy(param.mods | Flag.SYNTHETIC | Flag.ARTIFACT)
          else param

        val rewrite =
          q"$main.$method(($param2) => { ..$noUnusedVals; $result })"
        Some(replaceTree(arg, rewrite))

      case _ => None
    }
  }

  case class Tupled(
    main: Tree,
    method: TermName,
    param: ValDef,
    vals: List[Tree],
    usedNames: Tree,
    result: Tree
  )

  object TupleBinding {
    def unapply(arg: Tree): Option[Tupled] = arg match {
      case Apply(Select(TupleBinding(td @ Tupled(
      _,
      _,
      _,
      vals,
      _,
      TuplerBlock(moreVals)
      )),  TermName("map")), Untupler(used, ret) :: Nil) =>
        Some(td.copy(vals = vals ::: moreVals, result = ret, usedNames = used))

      case q"$main.map(${Tupler(param, vals)}).${m @ Untuplable()}(${Untupler(used, tree)})" if ForArtifact(arg) =>
        Some(Tupled(main, m, param, vals, used, tree))

      case _ =>
        None
    }
  }

  object Untuplable {
    def unapply(arg: Name): Boolean = arg match {
      case TermName("map") | TermName("flatMap") | TermName("foreach") =>
        true
      case _ =>
        false
    }
  }

  object TuplerBlock {
    def unapply(arg: Tree): Option[List[Tree]] = arg match {
      case Block(
        valDefs, Apply(Select(Ident(TermName("scala")), tn), _)
      ) if tn.startsWith("Tuple") && valDefs.forall(_.isInstanceOf[ValDef]) =>
        Some(valDefs)
      case _ => None
    }
  }

  object Tupler {
    def unapply(arg: Tree): Option[(ValDef, List[Tree])] = arg match {
      case Function(param :: Nil, TuplerBlock(valDefs)) =>
        Some((param, valDefs))
      case _ =>
        None
    }
  }

  object Untupler {
    def unapply(arg: Tree): Option[(Tree, Tree)] = arg match {
      case Function(_ :: Nil, Match(_,
        CaseDef(pat, _, body) :: Nil
      )) =>
        Some((pat, body))
      case _ =>
        None
    }
  }
}
