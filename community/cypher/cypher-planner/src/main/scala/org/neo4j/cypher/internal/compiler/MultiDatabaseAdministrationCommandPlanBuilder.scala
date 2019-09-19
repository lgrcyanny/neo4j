/*
 * Copyright (c) 2002-2019 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compiler

import org.neo4j.configuration.helpers.{DatabaseNameValidator, NormalizedDatabaseName}
import org.neo4j.cypher.internal.compiler.phases.{LogicalPlanState, PlannerContext}
import org.neo4j.cypher.internal.logical.plans
import org.neo4j.cypher.internal.logical.plans.{LogicalPlan, NameValidator, PrivilegePlan, ResolvedCall, SecurityAdministrationLogicalPlan}
import org.neo4j.cypher.internal.planner.spi.ProcedurePlannerName
import org.neo4j.cypher.internal.v4_0.ast._
import org.neo4j.cypher.internal.v4_0.ast.prettifier.{ExpressionStringifier, Prettifier}
import org.neo4j.cypher.internal.v4_0.ast.semantics.{SemanticCheckResult, SemanticState}
import org.neo4j.cypher.internal.v4_0.frontend.phases.CompilationPhaseTracer.CompilationPhase
import org.neo4j.cypher.internal.v4_0.frontend.phases.CompilationPhaseTracer.CompilationPhase.PIPE_BUILDING
import org.neo4j.cypher.internal.v4_0.frontend.phases._
import org.neo4j.cypher.internal.v4_0.util.InputPosition
import org.neo4j.cypher.internal.v4_0.util.attribution.SequentialIdGen
import org.neo4j.exceptions.InvalidArgumentException
import org.neo4j.string.UTF8

/**
  * This planner takes on queries that run at the DBMS level for multi-database administration
  */
case object MultiDatabaseAdministrationCommandPlanBuilder extends Phase[PlannerContext, BaseState, LogicalPlanState] {

  val prettifier = Prettifier(ExpressionStringifier())

  override def phase: CompilationPhase = PIPE_BUILDING

  override def description = "take on administrative queries that require no planning such as multi-database administration commands"

  override def postConditions: Set[Condition] = Set.empty

  override def process(from: BaseState, context: PlannerContext): LogicalPlanState = {
    implicit val idGen: SequentialIdGen = new SequentialIdGen()
    val maybeLogicalPlan: Option[LogicalPlan] = from.statement() match {
      // SHOW USERS
      case _: ShowUsers =>
        Some(plans.ShowUsers(Some(plans.AssertDbmsAdmin(ShowUserAction))))

      // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] WITH PASSWORD password
      case c@CreateUser(userName, Some(initialStringPassword), initialParameterPassword, requirePasswordChange, suspended, ifExistsDo) =>
        NameValidator.assertValidUsername(userName)
        val admin = Some(plans.AssertDbmsAdmin(CreateUserAction))
        val source = ifExistsDo match {
          case _: IfExistsReplace => Some(plans.DropUser(admin, userName))
          case _: IfExistsDoNothing => Some(plans.DoNothingIfExists(admin, "User", userName))
          case _ => admin
        }
        Some(plans.LogSystemCommand(
          plans.CreateUser(source, userName, Some(UTF8.encode(initialStringPassword)), initialParameterPassword, requirePasswordChange, suspended),
          prettifier.asString(c)))

      // CREATE [OR REPLACE] USER foo [IF NOT EXISTS] WITH PASSWORD $password
      case c@CreateUser(userName, None, initialParameterPassword, requirePasswordChange, suspended, ifExistsDo) =>
        NameValidator.assertValidUsername(userName)
        val admin = Some(plans.AssertDbmsAdmin(CreateUserAction))
        val source = ifExistsDo match {
          case _: IfExistsReplace => Some(plans.DropUser(admin, userName))
          case _: IfExistsDoNothing => Some(plans.DoNothingIfExists(admin, "User", userName))
          case _ => admin
        }
        Some(plans.LogSystemCommand(
          plans.CreateUser(source, userName, None, initialParameterPassword, requirePasswordChange, suspended),
          prettifier.asString(c)))

      // DROP USER foo [IF EXISTS]
      case c@DropUser(userName, ifExists) =>
        val admin = Some(plans.AssertDbmsAdmin(DropUserAction))
        val source = if (ifExists) Some(plans.DoNothingIfNotExists(admin, "User", userName)) else Some(plans.EnsureNodeExists(admin, "User", userName))
        Some(plans.LogSystemCommand(
          plans.DropUser(source, userName),
          prettifier.asString(c)))

      // ALTER USER foo
      case c@AlterUser(userName, Some(initialStringPassword), initialParameterPassword, requirePasswordChange, suspended) =>
        Some(plans.LogSystemCommand(
          plans.AlterUser(Some(plans.AssertDbmsAdmin(AlterUserAction)), userName, Some(UTF8.encode(initialStringPassword)), initialParameterPassword, requirePasswordChange, suspended),
          prettifier.asString(c)))

      // ALTER USER foo
      case c@AlterUser(userName, None, initialParameterPassword, requirePasswordChange, suspended) =>
        Some(plans.LogSystemCommand(
          plans.AlterUser(Some(plans.AssertDbmsAdmin(AlterUserAction)), userName, None, initialParameterPassword, requirePasswordChange, suspended),
          prettifier.asString(c)))

      // ALTER CURRENT USER SET PASSWORD FROM currentPassword TO newPassword
      case c@SetOwnPassword(Some(newStringPassword), newParameterPassword, Some(currentStringPassword), currentParameterPassword) =>
        Some(plans.LogSystemCommand(
          plans.SetOwnPassword(Some(UTF8.encode(newStringPassword)), newParameterPassword, Some(UTF8.encode(currentStringPassword)), currentParameterPassword),
          prettifier.asString(c)))

      // ALTER CURRENT USER SET PASSWORD FROM currentPassword TO $newPassword
      case c@SetOwnPassword(None, newParameterPassword, Some(currentStringPassword), currentParameterPassword) =>
        Some(plans.LogSystemCommand(
          plans.SetOwnPassword(None, newParameterPassword, Some(UTF8.encode(currentStringPassword)), currentParameterPassword),
          prettifier.asString(c)))

      // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO newPassword
      case c@SetOwnPassword(Some(newStringPassword), newParameterPassword, None, currentParameterPassword) =>
        Some(plans.LogSystemCommand(
          plans.SetOwnPassword(Some(UTF8.encode(newStringPassword)), newParameterPassword, None, currentParameterPassword),
          prettifier.asString(c)))

      // ALTER CURRENT USER SET PASSWORD FROM $currentPassword TO $newPassword
      case c@SetOwnPassword(None, newParameterPassword, None, currentParameterPassword) =>
        Some(plans.LogSystemCommand(
          plans.SetOwnPassword(None, newParameterPassword, None, currentParameterPassword),
          prettifier.asString(c)))

      // SHOW [ ALL | POPULATED ] ROLES [ WITH USERS ]
      case ShowRoles(withUsers, showAll) =>
        Some(plans.ShowRoles(Some(plans.AssertDbmsAdmin(ShowRoleAction)), withUsers, showAll))

      // CREATE [OR REPLACE] ROLE foo [IF NOT EXISTS]
      case c@CreateRole(roleName, None, ifExistsDo) =>
        NameValidator.assertValidRoleName(roleName)
        val admin = Some(plans.AssertDbmsAdmin(CreateRoleAction))
        val source = ifExistsDo match {
          case _: IfExistsReplace => Some(plans.DropRole(admin, roleName))
          case _: IfExistsDoNothing => Some(plans.DoNothingIfExists(admin, "Role", roleName))
          case _ => admin
        }
        Some(plans.LogSystemCommand(plans.CreateRole(source, roleName), prettifier.asString(c)))

      // CREATE [OR REPLACE] ROLE foo [IF NOT EXISTS] AS COPY OF bar
      case c@CreateRole(roleName, Some(fromName), ifExistsDo) =>
        NameValidator.assertValidRoleName(roleName)
        val admin = Some(plans.AssertDbmsAdmin(CreateRoleAction))
        val source = ifExistsDo match {
          case _: IfExistsReplace => Some(plans.DropRole(admin, roleName))
          case _: IfExistsDoNothing => Some(plans.DoNothingIfExists(admin, "Role", roleName))
          case _ => admin
        }
        Some(plans.LogSystemCommand(plans.CopyRolePrivileges(
          Some(plans.CopyRolePrivileges(
            Some(plans.CreateRole(
              Some(plans.RequireRole(source, fromName)), roleName)
            ), roleName, fromName, "GRANTED")
          ), roleName, fromName, "DENIED"), prettifier.asString(c)))

      // DROP ROLE foo [IF EXISTS]
      case c@DropRole(roleName, ifExists) =>
        val admin = Some(plans.AssertDbmsAdmin(DropRoleAction))
        val source = if (ifExists) Some(plans.DoNothingIfNotExists(admin, "Role", roleName)) else Some(plans.EnsureNodeExists(admin, "Role", roleName))
        Some(plans.LogSystemCommand(plans.DropRole(source, roleName), prettifier.asString(c)))

      // GRANT roles TO users
      case c@GrantRolesToUsers(roleNames, userNames) =>
        (for (userName <- userNames; roleName <- roleNames) yield {
          roleName -> userName
        }).foldLeft(Some(plans.AssertDbmsAdmin(GrantRoleAction).asInstanceOf[SecurityAdministrationLogicalPlan])) {
          case (source, (userName, roleName)) => Some(plans.GrantRoleToUser(source, userName, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // REVOKE roles FROM users
      case c@RevokeRolesFromUsers(roleNames, userNames) =>
        (for (userName <- userNames; roleName <- roleNames) yield {
          roleName -> userName
        }).foldLeft(Some(plans.AssertDbmsAdmin(RevokeRoleAction).asInstanceOf[SecurityAdministrationLogicalPlan])) {
          case (source, (userName, roleName)) => Some(plans.RevokeRoleFromUser(source, userName, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // GRANT ACCESS ON DATABASE foo TO role
      case c@GrantPrivilege(DatabasePrivilege(action), _, database, _, roleNames) =>
        roleNames.foldLeft(Some(plans.AssertDbmsAdmin(action).asInstanceOf[PrivilegePlan])) {
          case (source, roleName) => Some(plans.GrantAccess(source, database, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // DENY ACCESS ON DATABASE foo TO role
      case c@DenyPrivilege(DatabasePrivilege(action), _, database, _, roleNames) =>
        roleNames.foldLeft(Some(plans.AssertDbmsAdmin(action).asInstanceOf[PrivilegePlan])) {
          case (source, roleName) => Some(plans.DenyAccess(source, database, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // REVOKE ACCESS ON DATABASE foo FROM role
      case c@RevokePrivilege(DatabasePrivilege(action), _, database, _, roleNames, revokeType) =>
        roleNames.foldLeft(Some(plans.AssertDbmsAdmin(action).asInstanceOf[PrivilegePlan])) {
          case (source, roleName) => Some(plans.RevokeAccess(source, database, roleName, revokeType))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // GRANT TRAVERSE ON GRAPH foo ELEMENTS A (*) TO role
      case c@GrantPrivilege(TraversePrivilege(), _, database, segments, roleNames) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(GrantPrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.GrantTraverse(source, database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // DENY TRAVERSE ON GRAPH foo ELEMENTS A (*) TO role
      case c@DenyPrivilege(TraversePrivilege(), _, database, segments, roleNames) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(DenyPrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.DenyTraverse(source, database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // REVOKE TRAVERSE ON GRAPH foo ELEMENTS A (*) FROM role
      case c@RevokePrivilege(TraversePrivilege(), _, database, segments, roleNames, revokeType) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(RevokePrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.RevokeTraverse(source, database, segment, roleName, revokeType))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // GRANT WRITE {*} ON GRAPH foo ELEMENTS * (*) TO role
      case c@GrantPrivilege(WritePrivilege(), _, database, segments, roleNames) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(GrantPrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.GrantWrite(source, AllResource()(InputPosition.NONE), database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // DENY WRITE {*} ON GRAPH foo ELEMENTS * (*) TO role
      case c@DenyPrivilege(WritePrivilege(), _, database, segments, roleNames) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(DenyPrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.DenyWrite(source, AllResource()(InputPosition.NONE), database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // REVOKE WRITE {*} ON GRAPH foo ELEMENTS * (*) FROM role
      case c@RevokePrivilege(WritePrivilege(), _, database, segments, roleNames, revokeType) =>
        (for (roleName <- roleNames; segment <- segments.simplify) yield {
          roleName -> segment
        }).foldLeft(Some(plans.AssertDbmsAdmin(RevokePrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, segment)) => Some(plans.RevokeWrite(source, AllResource()(InputPosition.NONE), database, segment, roleName, revokeType))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // GRANT READ {prop} ON GRAPH foo ELEMENTS A (*) TO role
      // GRANT MATCH {prop} ON GRAPH foo ELEMENTS A (*) TO role
      case c@GrantPrivilege(privilege, resources, database, segments, roleNames) =>
        val combos = for (roleName <- roleNames; segment <- segments.simplify; resource <- resources.simplify) yield {
          roleName -> (segment, resource)
        }
        val isAdmin = Some(plans.AssertDbmsAdmin(GrantPrivilegeAction).asInstanceOf[PrivilegePlan])
        val plan = privilege match {
          case ReadPrivilege() => isAdmin
          case MatchPrivilege() => combos.foldLeft(isAdmin) {
            case (source, (roleName, (segment, _))) => Some(plans.GrantTraverse(source, database, segment, roleName))
          }
        }
        combos.foldLeft(plan) {
          case (source, (roleName, (segment, resource))) => Some(plans.GrantRead(source, resource, database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // DENY READ {prop} ON GRAPH foo ELEMENTS A (*) TO role
      // DENY MATCH {prop} ON GRAPH foo ELEMENTS A (*) TO role
      case c@DenyPrivilege(privilege, resources, database, segments, roleNames) =>
        val combos = for (roleName <- roleNames; segment <- segments.simplify; resource <- resources.simplify) yield {
          roleName -> (segment, resource)
        }
        val isAdmin = Some(plans.AssertDbmsAdmin(DenyPrivilegeAction).asInstanceOf[PrivilegePlan])
        val plan = privilege match {
          case MatchPrivilege()  if resources.isInstanceOf[AllResource] => combos.foldLeft(isAdmin) {
            case (source, (roleName, (segment, _))) => Some(plans.DenyTraverse(source, database, segment, roleName))
          }
          case _ => isAdmin
        }
        combos.foldLeft(plan) {
          case (source, (roleName, (segment, resource))) => Some(plans.DenyRead(source, resource, database, segment, roleName))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // REVOKE READ {prop} ON GRAPH foo ELEMENTS A (*) FROM role
      // REVOKE MATCH {prop} ON GRAPH foo ELEMENTS A (*) FROM role
      case c@RevokePrivilege(_, resources, database, segments, roleNames, revokeType) =>
        (for (roleName <- roleNames; segment <- segments.simplify; resource <- resources.simplify) yield {
          roleName -> (segment, resource)
        }).foldLeft(Some(plans.AssertDbmsAdmin(RevokePrivilegeAction).asInstanceOf[PrivilegePlan])) {
          case (source, (roleName, (segment, resource))) => Some(plans.RevokeRead(source, resource, database, segment, roleName, revokeType))
        }.map(plan => plans.LogSystemCommand(plan, prettifier.asString(c)))

      // SHOW [ALL | USER user | ROLE role] PRIVILEGES
      case ShowPrivileges(scope) =>
        Some(plans.ShowPrivileges(Some(plans.AssertDbmsAdmin(ShowPrivilegeAction)), scope))

      // SHOW DATABASES
      case _: ShowDatabases =>
        Some(plans.ShowDatabases())

      // SHOW DEFAULT DATABASE
      case _: ShowDefaultDatabase =>
        Some(plans.ShowDefaultDatabase())

      // SHOW DATABASE foo
      case ShowDatabase(dbName) =>
        Some(plans.ShowDatabase(new NormalizedDatabaseName(dbName)))

      // CREATE [OR REPLACE] DATABASE foo [IF NOT EXISTS]
      case CreateDatabase(dbName, ifExistsDo) =>
        val normalizedName = new NormalizedDatabaseName(dbName)
        try {
          DatabaseNameValidator.assertValidDatabaseName(normalizedName)
        } catch {
          case e: IllegalArgumentException => throw new InvalidArgumentException(e.getMessage)
        }
        val admin = Some(plans.AssertDbmsAdmin(CreateDatabaseAction))
        val source = ifExistsDo match {
          case _: IfExistsReplace => Some(plans.DropDatabase(admin, normalizedName))
          case _: IfExistsDoNothing => Some(plans.DoNothingIfExists(admin, "Database", normalizedName.name()))
          case _ => admin
        }
        Some(plans.EnsureValidNumberOfDatabases(
          Some(plans.CreateDatabase(source, normalizedName))))

      // DROP DATABASE foo [IF EXISTS]
      case DropDatabase(dbName, ifExists) =>
        val normalizedName = new NormalizedDatabaseName(dbName)
        val admin = Some(plans.AssertDbmsAdmin(DropDatabaseAction))
        val source = if (ifExists) Some(plans.DoNothingIfNotExists(admin, "Database", normalizedName.name())) else admin
        Some(plans.DropDatabase(
          Some(plans.EnsureValidNonSystemDatabase(source, normalizedName, "delete")), normalizedName))

      // START DATABASE foo
      case StartDatabase(dbName) =>
        val normalizedName = new NormalizedDatabaseName(dbName)
        Some(plans.StartDatabase(Some(plans.AssertDatabaseAdmin(StartDatabaseAction, normalizedName)), normalizedName))

      // STOP DATABASE foo
      case StopDatabase(dbName) =>
        val normalizedName = new NormalizedDatabaseName(dbName)
        Some(plans.StopDatabase(
          Some(plans.EnsureValidNonSystemDatabase(
            Some(plans.AssertDatabaseAdmin(StopDatabaseAction, normalizedName)), normalizedName, "stop")), normalizedName))

      // Global call: CALL foo.bar.baz("arg1", 2) // only if system procedure is allowed!
      case Query(None, SingleQuery(Seq(resolved@ResolvedCall(signature, _, _, _, _),Return(_,_,_,_,_,_)))) if signature.systemProcedure =>
        val SemanticCheckResult(_, errors) = resolved.semanticCheck(SemanticState.clean)
        errors.foreach { error => throw context.cypherExceptionFactory.syntaxException(error.msg, error.position) }
        Some(plans.SystemProcedureCall(signature.name.toString, from.queryText, context.params))

      case _ => None
    }

    val planState = LogicalPlanState(from)

    if (maybeLogicalPlan.isDefined)
      planState.copy(maybeLogicalPlan = maybeLogicalPlan, plannerName = ProcedurePlannerName)
    else planState
  }
}

case object UnsupportedSystemCommand extends Phase[PlannerContext, BaseState, LogicalPlanState] {
  override def phase: CompilationPhase = PIPE_BUILDING

  override def description: String = "Unsupported system command"

  override def postConditions: Set[Condition] = Set.empty

  override def process(from: BaseState, context: PlannerContext): LogicalPlanState = throw new RuntimeException(s"Not a recognised system command or procedure: ${from.queryText}")
}