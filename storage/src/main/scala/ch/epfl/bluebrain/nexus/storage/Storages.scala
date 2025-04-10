package ch.epfl.bluebrain.nexus.storage

import akka.http.scaladsl.model.Uri
import akka.stream.Materializer
import akka.stream.alpakka.file.scaladsl.Directory
import akka.stream.scaladsl.{FileIO, Keep, Sink}
import cats.effect.IO
import ch.epfl.bluebrain.nexus.storage.File._
import ch.epfl.bluebrain.nexus.storage.Rejection.{PathAlreadyExists, PathContainsLinks, PathNotFound}
import ch.epfl.bluebrain.nexus.storage.StorageError.{InternalError, PathInvalid, PermissionsFixingFailed}
import ch.epfl.bluebrain.nexus.storage.Storages.BucketExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.PathExistence._
import ch.epfl.bluebrain.nexus.storage.Storages.{BucketExistence, PathExistence}
import ch.epfl.bluebrain.nexus.storage.attributes.AttributesComputation._
import ch.epfl.bluebrain.nexus.storage.attributes.{AttributesCache, ContentTypeDetector}
import ch.epfl.bluebrain.nexus.storage.config.AppConfig.{DigestConfig, StorageConfig}

import java.net.URLDecoder
import java.nio.file.StandardCopyOption._
import java.nio.file.{Files, Path, Paths}
import java.security.MessageDigest
import scala.annotation.tailrec
import scala.concurrent.{ExecutionContext, Future}
import scala.sys.process._
import scala.util.{Success, Try}

trait Storages[Source] {

  /**
    * Checks that the provided bucket name exists and it is readable/writable.
    *
    * @param name
    *   the storage bucket name
    */
  def exists(name: String): BucketExistence

  /**
    * Check whether the provided path already exists.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    */
  def pathExists(name: String, path: Uri.Path): PathExistence

  /**
    * Creates a file with the provided ''metadata'' and ''source'' on the provided ''filePath''.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path location
    * @param source
    *   the file content
    * @return
    *   The file attributes containing the metadata (bytes and location) wrapped in an F effect type
    */
  def createFile(
      name: String,
      path: Uri.Path,
      source: Source
  )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[FileAttributes]

  /**
    * Moves a path from the provided ''sourcePath'' to ''destPath'' inside the nexus folder.
    *
    * @param name
    *   the storage bucket name
    * @param sourcePath
    *   the source path location
    * @param destPath
    *   the destination path location inside the nexus folder
    * @return
    *   Left(rejection) or Right(fileAttributes). The file attributes contain the metadata (bytes and location) wrapped
    *   in an F effect type
    */
  def moveFile(
      name: String,
      sourcePath: Uri.Path,
      destPath: Uri.Path
  )(implicit bucketEv: BucketExists): IO[Either[Rejection, FileAttributes]]

  /**
    * Retrieves the file as a Source.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path to the file location
    * @return
    *   Left(rejection), Right(source, Some(filename)) when the path is a file and Right(source, None) when the path is
    *   a directory
    */
  def getFile(
      name: String,
      path: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathExists): RejOr[(Source, Option[String])]

  /**
    * Retrieves the attributes of the file.
    *
    * @param name
    *   the storage bucket name
    * @param path
    *   the path to the file location
    */
  def getAttributes(
      name: String,
      path: Uri.Path
  )(implicit bucketEv: BucketExists, pathEv: PathExists): IO[FileAttributes]

}

object Storages {

  /**
    * Checks if the ''target'' path is a descendant of the ''parent'' path. E.g.: path = /some/my/path ; parent = /some
    * will return true E.g.: path = /some/my/path ; parent = /other will return false
    */
  private def descendantOf(target: Path, parent: Path): Boolean =
    inner(parent, target.getParent)

  @tailrec
  @SuppressWarnings(Array("NullParameter"))
  private def inner(parent: Path, child: Path): Boolean = {
    if (child == null) false
    else if (parent == child) true
    else inner(parent, child.getParent)
  }

  sealed trait BucketExistence
  sealed trait PathExistence

  object BucketExistence {
    final case object BucketExists       extends BucketExistence
    final case object BucketDoesNotExist extends BucketExistence
    type BucketExists       = BucketExists.type
    type BucketDoesNotExist = BucketDoesNotExist.type
  }

  object PathExistence {
    final case object PathExists       extends PathExistence
    final case object PathDoesNotExist extends PathExistence
    type PathExists       = PathExists.type
    type PathDoesNotExist = PathDoesNotExist.type
  }

  /**
    * An Disk implementation of Storage interface.
    */
  final class DiskStorage(
      config: StorageConfig,
      contentTypeDetector: ContentTypeDetector,
      digestConfig: DigestConfig,
      cache: AttributesCache
  )(implicit
      ec: ExecutionContext,
      mt: Materializer
  ) extends Storages[AkkaSource] {

    private def decode(path: Uri.Path): String =
      Try(URLDecoder.decode(path.toString, "UTF-8")).getOrElse(path.toString())

    private def basePath(name: String, protectedDir: Boolean = true): Path = {
      val path = config.rootVolume.resolve(name).normalize()
      if (protectedDir) path.resolve(config.protectedDirectory).normalize() else path
    }

    private def filePath(name: String, path: Uri.Path, protectedDir: Boolean = true): Path = {
      val filePath = Paths.get(decode(path))
      if (filePath.isAbsolute) filePath.normalize()
      else basePath(name, protectedDir).resolve(filePath).normalize()
    }

    def exists(name: String): BucketExistence = {
      val path = basePath(name)
      if (path.getParent.getParent != config.rootVolume) BucketDoesNotExist
      else if (Files.isDirectory(path) && Files.isReadable(path)) BucketExists
      else BucketDoesNotExist
    }

    def pathExists(name: String, path: Uri.Path): PathExistence = {
      val absPath = filePath(name, path)
      if (Files.exists(absPath) && Files.isReadable(absPath) && descendantOf(absPath, basePath(name))) PathExists
      else PathDoesNotExist
    }

    def createFile(
        name: String,
        path: Uri.Path,
        source: AkkaSource
    )(implicit bucketEv: BucketExists, pathEv: PathDoesNotExist): IO[FileAttributes] = {
      val absFilePath = filePath(name, path)
      if (descendantOf(absFilePath, basePath(name)))
        IO.fromTry(Try(Files.createDirectories(absFilePath.getParent))) >>
          IO.fromTry(Try(MessageDigest.getInstance(digestConfig.algorithm))).flatMap { msgDigest =>
            IO.fromFuture(
              IO.delay(
                source
                  .alsoToMat(sinkDigest(msgDigest))(Keep.right)
                  .toMat(FileIO.toPath(absFilePath)) { case (digFuture, ioFuture) =>
                    digFuture.zipWith(ioFuture) {
                      case (digest, io) if absFilePath.toFile.exists() =>
                        Future(
                          FileAttributes(absFilePath.toAkkaUri, io.count, digest, contentTypeDetector(absFilePath))
                        )
                      case _                                           =>
                        Future.failed(InternalError(s"I/O error writing file to path '$path'"))
                    }
                  }
                  .run()
                  .flatten
              )
            )
          }
      else
        IO.raiseError(PathInvalid(name, path))
    }

    def moveFile(
        name: String,
        sourcePath: Uri.Path,
        destPath: Uri.Path
    )(implicit bucketEv: BucketExists): IO[Either[Rejection, FileAttributes]] = {

      val bucketPath          = basePath(name, protectedDir = false)
      val bucketProtectedPath = basePath(name)
      val absSourcePath       = filePath(name, sourcePath, protectedDir = false)
      val absDestPath         = filePath(name, destPath)

      def fixPermissions(path: Path): IO[Either[PermissionsFixingFailed, Unit]] =
        if (config.fixerEnabled) {
          val absPath  = path.toAbsolutePath.normalize.toString
          val process  = Process(config.fixerCommand :+ absPath)
          val logger   = StringProcessLogger(config.fixerCommand, absPath)
          val exitCode = process ! logger
          if (exitCode == 0) IO.pure(Right(()))
          else IO.pure(Left(PermissionsFixingFailed(absPath, logger.toString)))
        } else {
          IO.pure(Right(()))
        }

      def failOrComputeSize(
          fixPermsResult: Either[PermissionsFixingFailed, Unit],
          isDir: Boolean
      ): IO[RejOrAttributes] =
        fixPermsResult match {
          case Left(err) => IO.raiseError(err)
          case Right(_)  => computeSizeAndMove(isDir)
        }

      def computeSizeAndMove(isDir: Boolean): IO[RejOrAttributes] = {
        lazy val mediaType = contentTypeDetector(absDestPath, isDir)
        size(absSourcePath).flatMap { computedSize =>
          IO.fromTry(Try(Files.createDirectories(absDestPath.getParent))) >>
            IO.fromTry(Try(Files.move(absSourcePath, absDestPath, ATOMIC_MOVE))) >>
            IO.pure(cache.asyncComputePut(absDestPath, digestConfig.algorithm)) >>
            IO.pure(Right(FileAttributes(absDestPath.toAkkaUri, computedSize, Digest.empty, mediaType)))
        }
      }

      def dirContainsLink(path: Path): IO[Boolean] =
        IO.fromFuture(
          IO.delay(
            Directory
              .walk(path)
              .map(p => Files.isSymbolicLink(p) || containsHardLink(p))
              .takeWhile(_ == false, inclusive = true)
              .runWith(Sink.last)
          )
        )

      def allowedPrefix(absSourcePath: Path) =
        absSourcePath.startsWith(bucketPath) ||
          config.extraPrefixes.exists(absSourcePath.startsWith)

      fixPermissions(absSourcePath).flatMap { fixPermsResult =>
        if (!Files.exists(absSourcePath))
          IO.pure(Left(PathNotFound(name, sourcePath)))
        else if (descendantOf(absSourcePath, bucketProtectedPath))
          IO.pure(Left(PathNotFound(name, sourcePath)))
        else if (!allowedPrefix(absSourcePath))
          IO.raiseError(PathInvalid(name, sourcePath))
        else if (!descendantOf(absDestPath, bucketProtectedPath))
          IO.raiseError(PathInvalid(name, destPath))
        else if (Files.exists(absDestPath))
          IO.pure(Left(PathAlreadyExists(name, destPath)))
        else if (Files.isSymbolicLink(absSourcePath) || containsHardLink(absSourcePath))
          IO.pure(Left(PathContainsLinks(name, sourcePath)))
        else if (Files.isRegularFile(absSourcePath))
          failOrComputeSize(fixPermsResult, isDir = false)
        else if (Files.isDirectory(absSourcePath))
          dirContainsLink(absSourcePath).flatMap {
            case true  => IO.pure(Left(PathContainsLinks(name, sourcePath)))
            case false => failOrComputeSize(fixPermsResult, isDir = true)
          }
        else IO.pure(Left(PathNotFound(name, sourcePath)))
      }
    }

    def getFile(
        name: String,
        path: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathExists): RejOr[(AkkaSource, Option[String])] = {
      val absPath = filePath(name, path)
      if (Files.isRegularFile(absPath)) Right(fileSource(absPath) -> Some(absPath.getFileName.toString))
      else if (Files.isDirectory(absPath)) Right(folderSource(absPath) -> None)
      else Left(PathNotFound(name, path))
    }

    def getAttributes(
        name: String,
        path: Uri.Path
    )(implicit bucketEv: BucketExists, pathEv: PathExists): IO[FileAttributes] =
      cache.get(filePath(name, path))

    private def containsHardLink(absPath: Path): Boolean =
      if (Files.isDirectory(absPath)) false
      else
        Try(Files.getAttribute(absPath, "unix:nlink").asInstanceOf[Int]) match {
          case Success(value) => value > 1
          case _              => false
        }

    private def size(absPath: Path): IO[Long] =
      if (Files.isDirectory(absPath)) {
        IO.fromFuture(
          IO.delay(
            Directory.walk(absPath).filter(Files.isRegularFile(_)).runFold(0L)(_ + Files.size(_))
          )
        )
      } else if (Files.isRegularFile(absPath))
        IO.pure(Files.size(absPath))
      else
        IO.raiseError(InternalError(s"Path '$absPath' is not a file nor a directory"))
  }

}
