package edu.gemini.sp.vcs.log.impl

import edu.gemini.sp.vcs.log._
import edu.gemini.spModel.core.SPProgramID
import edu.gemini.util.security.principal.{StaffPrincipal, UserPrincipal, GeminiPrincipal}
import doobie.imports._
import java.io.File
import java.sql.Timestamp
import java.util.logging.Logger
import scalaz._, Scalaz._, effect.IO

object PersistentVcsLog2 {
  import PersistentVcsMappers._

  lazy val Log = Logger.getLogger(getClass.getName)

  final val Anonymous: NonEmptyList[GeminiPrincipal] = NonEmptyList(UserPrincipal("Anonymous"))

  val TimeSlice = 1000 * 60 * 60 // 1 hour

  def info(s: String): ConnectionIO[Unit] =
    FC.delay(Log.info(s))

  def warn(s: String): ConnectionIO[Unit] =
    FC.delay(Log.warning(s))

  def fail[A](s: String): ConnectionIO[A] =
    FC.delay(sys.error(s))

  // The idea here is that when we change the schema, we update this number and add a case to the upgradeFrom
  // function below. This may end up being difficult in practice but at least we have a mechanism to do it.
  val SchemaVersion = 4

  // These are DB-specific, sadly
  val DUPLICATE_KEY = SqlState("what is it?")
  val TABLE_OR_VIEW_NOT_FOUND = SqlState("42S02")

  val createSchema: ConnectionIO[Unit] =
    sql"""
      create table VERSION (
        VALUE INTEGER NOT NULL
      );

      create table PRINCIPAL (
        PRINCIPAL_ID INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 1) NOT NULL PRIMARY KEY,
        CLASS VARCHAR NOT NULL,
        NAME VARCHAR NOT NULL
      );      
      create unique index PRINCIPAL_IDX on PRINCIPAL (CLASS,NAME);
      
      create table EVENT (
        EVENT_ID INTEGER GENERATED BY DEFAULT AS IDENTITY(START WITH 1) NOT NULL PRIMARY KEY,
        OP VARCHAR NOT NULL,
        TIMESTAMP TIMESTAMP NOT NULL,
        PROGRAM_ID VARCHAR NOT NULL,
        PRINCIPAL_HASH VARCHAR NOT NULL
      );
      
      create table EVENT_PRINCIPAL (
        EVENT_ID INTEGER NOT NULL,
        PRINCIPAL_ID INTEGER NOT NULL
      );
      create unique index EVENT_PRINCIPAL_IDX on EVENT_PRINCIPAL (EVENT_ID,PRINCIPAL_ID);
      
      alter table EVENT_PRINCIPAL
      add constraint EVENT_PRINCIPAL_FK1 
      foreign key(EVENT_ID) references EVENT(EVENT_ID) 
      on update NO ACTION 
      on delete NO ACTION;
      
      alter table EVENT_PRINCIPAL 
      add constraint EVENT_PRINCIPAL_FK3 
      foreign key(PRINCIPAL_ID) references PRINCIPAL(PRINCIPAL_ID) 
      on update NO ACTION 
      on delete NO ACTION;
    """.update.run.void

  def insertSchemaVersion(version: Int): ConnectionIO[Unit] =
    sql"insert into VERSION (VALUE) values ($version)".update.run.void

  val getSchemaVersion: ConnectionIO[Int] =
    sql"select VALUE from VERSION".query[Int].unique

  def checkSchema(path: String): ConnectionIO[Unit] =
    open(path) exceptSomeSqlState {
      case TABLE_OR_VIEW_NOT_FOUND => createNewDatabase
    }

  def createNewDatabase: ConnectionIO[Unit] =
    for {
      _ <- info("This is a new database. Creating schema...")
      _ <- createSchema
      _ <- insertSchemaVersion(SchemaVersion)
    } yield ()

  def open(path:String): ConnectionIO[Unit] =
    for {
      v <- getSchemaVersion
      _ <- info(s"Opened database with schema version $SchemaVersion on ${path}")
      _ <- (v != SchemaVersion).whenM(upgradeFrom(v) >> checkSchema(path))
    } yield ()

  def upgradeFrom(version: Int): ConnectionIO[Unit] =
    version match {

      case 1 =>
        sql""";
          update EVENT set op = 'Fetch' where op = 'OpFetch';
          update EVENT set op = 'Store' where op = 'OpStore';
          update VERSION set VALUE = 2;
        """.update.run.void

      case 2 =>
        warn("Major upgrade; dropping all event data.") *>
        sql"""
          delete from EVENT_PRINCIPAL;
          delete from EVENT;
          alter table EVENT add (PRINCIPAL_HASH varchar NOT NULL DEFAULT '');
          update VERSION set VALUE = 3;
        """.update.run.void

      case 3 =>
        sql"""
          alter table EVENT add (PRINCIPAL_HASH varchar NOT NULL DEFAULT '')";
          update VERSION set VALUE = 4";
        """.update.run.void

      // Newer versions here

      case n => 
        fail(s"Don't know how to upgrade from version $version.")

    }

  def insertJoin(eid: Id[VcsEvent], pid: Id[GeminiPrincipal]): ConnectionIO[Int] =
    sql"""
      insert into EVENT_PRINCIPAL (EVENT_ID, PRINCIPAL_ID)
      values ($eid, $pid)
    """.update.run

  // OCSINF-118: if the principal set is empty, add an anonymous principal
  def doLog(op: VcsOp, time:Timestamp, pid: SPProgramID, principals: List[GeminiPrincipal]): ConnectionIO[VcsEvent] =
    doLog2(op, time, pid, principals.toNel.getOrElse(Anonymous))

  // Log implementation. Insert the event, insert the principals, hook them up, read it back.
  def doLog2(op: VcsOp, time:Timestamp, pid: SPProgramID, principals: NonEmptyList[GeminiPrincipal]): ConnectionIO[VcsEvent] = 
    for {
      ids <- principals.traverse(insertPrincipal)
      eid <- insertEvent(op, time, pid, PersistentVcsUtil.setHash(ids.map(_.n)))
      _   <- ids.traverse(insertJoin(eid, _))
      e   <- selectEvent(eid)
    } yield e

  // An uninspiring type that we're selecting twice below.
  type U = ((Id[VcsEvent], VcsOp, Timestamp, SPProgramID, String), (String, String))

  // To select by program we join with the principal table and stream results back, chunking by 
  // program and principals and then decoding into a stream of event sets. We can then drop the
  // offset and take the size. This is rather complex and should be revisited.
  def doSelectByProgram(pid: SPProgramID, offset: Int, size: Int): ConnectionIO[(List[VcsEventSet], Boolean)] =
    sql"""
      select   E.EVENT_ID, E.OP, E.TIMESTAMP, E.PROGRAM_ID, E.PRINCIPAL_HASH, P.CLASS, P.NAME
      from     EVENT E
      join     EVENT_PRINCIPAL J on J.EVENT_ID = E.EVENT_ID
      join     PRINCIPAL P on P.PRINCIPAL_ID = J.PRINCIPAL_ID
      where    E.PROGRAM_ID = $pid
      order by E.EVENT_ID desc
    """.query[U].process.chunkBy2 { 
      case (((_, _, ts0, pid0, ph0), _), ((_, _, ts1, pid1, ph1), _)) =>
        (ts0.getTime - ts1.getTime < TimeSlice) && (pid0 == pid1) && (ph0 == ph1)
    } .map(decode2)
      .drop(offset)
      .take(size + 1)
      .vector
      .map { v => 
        (v.take(size).toList, v.size > size) 
      }

  // Selecting a single event is a special case of the above, and uses the same decoder.
  def selectEvent(id: Id[VcsEvent]): ConnectionIO[VcsEvent] = 
    sql"""
      select E.EVENT_ID, E.OP, E.TIMESTAMP, E.PROGRAM_ID, E.PRINCIPAL_HASH, P.CLASS, P.NAME
      from   EVENT E
      join   EVENT_PRINCIPAL J on J.EVENT_ID = E.EVENT_ID
      join   PRINCIPAL P on P.PRINCIPAL_ID = J.PRINCIPAL_ID
      where  E.EVENT_ID = $id
    """.query[U].list.map(decode)

  // Decode a chunk of rows, which must be uniform and no-empty.
  def decode(chunk: List[U]): VcsEvent = {
    val ps = chunk.map(_._2).map(p => GeminiPrincipal(p._1, p._2))
    chunk.head._1 match {
      case (id, op, ts, pid, _) => VcsEvent(id.n, op, ts.getTime, pid, ps.toSet)
    }
  }

  // Decode a chunk of rows, which must be uniform and no-empty.
  def decode2(chunk: Vector[U]): VcsEventSet = {

    // Pull rollup data out of the chunk
    val ids:Set[Int] = chunk.map(_._1._1.n).toSet
    val ops:Map[VcsOp, Int] = chunk.map(_._1._2).groupBy(identity).mapValues(_.length)
    val tss:Set[Long] = chunk.map(_._1._3.getTime).toSet
    val pid:SPProgramID = chunk.head._1._4
    val gps:Set[GeminiPrincipal] = chunk.map(_._2).map(p => GeminiPrincipal(p._1, p._2)).toSet

    // And construct our event set!
    VcsEventSet(
      ids.min to ids.max,
      ops.toSeq.toMap, // Hack: ops is actually a MapLike and isn't serializable
      (tss.min, tss.max),
      pid,
      gps)

  }

  // Insert the event and return its Id
  def insertEvent(op: VcsOp, time: Timestamp, pid: SPProgramID, principalHash:String): ConnectionIO[Id[VcsEvent]] =
    sql"""
      insert into EVENT (OP, TIMESTAMP, PROGRAM_ID, PRINCIPAL_HASH)
      values ($op, $time, $pid, $principalHash)
    """.update.withUniqueGeneratedKeys[Id[VcsEvent]]("EVENT_ID")

  // Canonicalize a principal. To be more efficient we do the lookup first, and if that fails we
  // insert. This means we there's a race we need to handle.
  def insertPrincipal(p: GeminiPrincipal): ConnectionIO[Id[GeminiPrincipal]] =
    lookupPrincipal(p) >>= {
      case Some(id) => id.point[ConnectionIO]
      case None     =>
        sql"""
          insert into PRINCIPAL (CLASS, NAME)
          values (${p.clazz}, ${p.getName})
        """.update
           .withUniqueGeneratedKeys[Id[GeminiPrincipal]]("PRINCIPAL_ID")
           .exceptSomeSqlState {
          case DUPLICATE_KEY => insertPrincipal(p)
        }
    }

  // Look up a principla by name and class.
  def lookupPrincipal(gp: GeminiPrincipal): ConnectionIO[Option[Id[GeminiPrincipal]]] =
    sql"""
      select PRINCIPAL_ID
      from   PRINCIPAL
      where  CLASS = ${gp.clazz}
      and    NAME  = ${gp.getName}
    """.query[Id[GeminiPrincipal]].option

  def doArchive(f: File): ConnectionIO[Unit] =
    sql"""backup to ${f.getAbsolutePath}""".update.run.void

}

