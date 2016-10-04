package edu.gemini.catalog.image

import java.io._
import java.net.URL
import java.nio.file.{Files, Path, StandardCopyOption}
import java.util.logging.Logger

import edu.gemini.spModel.core.{Angle, Coordinates, Declination, RightAscension}
import nom.tam.fits.{Fits, Header}

import scala.util.matching.Regex
import scala.math._
import scala.reflect.ClassTag
import scalaz.Scalaz._
import scalaz._
import scalaz.concurrent.Task

case class AngularSize(width: Angle, height: Angle) {
  def this(size: Angle) = this(size, size)
}

case object AngularSize {
  implicit val equal = Equal.equalA[AngularSize]
}

/**
  * Query to request an image for a catalog and coordinates
  */
case class ImageSearchQuery(catalog: ImageCatalog, coordinates: Coordinates, size: AngularSize) {
  import ImageSearchQuery._

  def url: NonEmptyList[URL] = catalog.queryUrl(coordinates)

  def fileName(extension: String): String = s"img_${catalog.id}_${coordinates.toFilePart}_${size.toFilePart}.$extension"

  def isNearby(query: ImageSearchQuery): Boolean =
    catalog === query.catalog && isNearby(query.coordinates)

  def isNearby(c: Coordinates): Boolean =
    coordinates.angularDistance(c) <= maxDistance
}

object ImageSearchQuery {
  implicit val equals: Equal[ImageSearchQuery] = Equal.equalA[ImageSearchQuery]
  val maxDistance: Angle = Angle.fromArcmin(4.5)

  implicit class DeclinationShow(val d: Declination) extends AnyVal {
    def toFilePart: String = Declination.formatDMS(d, ":", 2)
  }

  implicit class RightAscensionShow(val a: RightAscension) extends AnyVal {
    def toFilePart: String = a.toAngle.formatHMS
  }

  implicit class CoordinatesShow(val c: Coordinates) extends AnyVal {
    def toFilePart: String = s"ra_${c.ra.toFilePart}_dec_${c.dec.toFilePart}"
  }

  implicit class AngularSizeShow(val s: AngularSize) extends AnyVal {
    def toFilePart: String = f"w_${s.width.toArcsecs.toInt}_h_${s.height.toArcsecs.toInt}"
  }
}

/**
  * Image in the file system
  */
case class ImageInFile(query: ImageSearchQuery, file: Path, fileSize: Long) {
  def contains(c: Coordinates): Boolean = {
    val gap = query.catalog.overlapGap
    // Convert everything to doubles, Angle complicates things
    val Δλ = ((query.size.width / 2).getOrElse(Angle.zero) - gap).toDegrees
    val Δφ = ((query.size.height / 2).getOrElse(Angle.zero) - gap).toDegrees
    val φ = c.dec.toDegrees
    val λ = c.ra.toAngle.toDegrees
    val φ0 = query.coordinates.dec.toDegrees
    val λ0 = query.coordinates.ra.toAngle.toDegrees
    val φ1 = φ0 + Δφ
    val λ1 = λ0 + Δλ
    val φ2 = φ0 - Δφ
    val λ2 = λ0 - Δλ

    (φ1 >= φ && φ2 <= φ) && (λ1 >= λ && λ2 <= λ)
  }
}

object ImageInFile {
  implicit val equals: Equal[ImageInFile] = Equal.equalA[ImageInFile]

  val fileRegex: Regex = """img_(.*)_ra_(.*)_dec_(.*)_w_(\d*)_h_(\d*)\.fits.*""".r

  /**
    * Decode a file name into an image entry
    */
  def entryFromFile(file: File): Option[ImageInFile] = {
    file.getName match {
      case fileRegex(c, raStr, decStr, w, h) =>
        for {
          catalog <- ImageCatalog.byId(c)
          ra      <- Angle.parseHMS(raStr).map(RightAscension.fromAngle).toOption
          dec     <- Angle.parseDMS(decStr).toOption.map(_.toDegrees).flatMap(Declination.fromDegrees)
          width   <- Angle.fromArcsecs(w.toInt).some
          height  <- Angle.fromArcsecs(h.toInt).some
        } yield ImageInFile(ImageSearchQuery(catalog, Coordinates(ra, dec), AngularSize(width, height)), file.toPath, file.length())
      case _                           => None
    }
  }
}

/**
  * Downloads images from a remote server and stores them in the file system
  */
object ImageCatalogClient {
  val Log: Logger = Logger.getLogger(this.getClass.getName)

  /**
    * Downloads the given image URL to a temporary file and returns the file
    */
  def downloadImageToFile(cacheDir: Path, url: URL, query: ImageSearchQuery): Task[ImageInFile] = {

    case class ConnectionDescriptor(contentType: Option[String], contentEncoding: Option[String]) {
      def extension: String = (contentEncoding, contentType) match {
          case (Some("x-gzip"), _)                                                              => "fits.gz"
          // REL-2776 At some places on the sky DSS returns an error, the HTTP error code is ok but the body contains no image
          case (None, Some(s)) if s.contains("text/html") && url.getPath.contains("dss_search") => throw new RuntimeException("Image not found at image server")
          case (None, Some(s)) if s.endsWith("fits")                                            => "fits"
          case _                                                                                => "tmp"
        }

      def compressed: Boolean = contentEncoding === "x-gzip".some
    }

    def createTmpFile: Task[File] = Task.delay {
      File.createTempFile(".img", ".fits", cacheDir.toFile)
    }

    def openConnection: Task[ConnectionDescriptor] = Task.delay {
      Log.info(s"Downloading image at $url")
      val connection = url.openConnection()
      ConnectionDescriptor(Option(connection.getContentType), Option(connection.getContentEncoding))
    }

    def writeToTempFile(file: File): Task[Unit] = Task.delay {
      val out = new FileOutputStream(file)
      val in = url.openStream()
      try {
        val buffer = new Array[Byte](8 * 1024)
        Iterator
          .continually(in.read(buffer))
          .takeWhile(-1 != _)
          .foreach(read => out.write(buffer, 0, read))
      } finally {
        out.close()
        in.close()
      }
    }

    /**
      * Attempts to calculate the center and size of the query from the actual header
      */
    def parseHeader(descriptor: ConnectionDescriptor, tmpFile: File): Task[ImageSearchQuery] = Task.delay {
      FitsHeadersParser.parseFitsGeometry(tmpFile, descriptor.compressed).map { g =>
        g.bifoldLeft(query)
          { (q, c) => c.fold(q)(c => q.copy(coordinates = c)) }
          { (q, s) => s.fold(q)(s => q.copy(size = s )) }
      }.getOrElse(query)
    }

    def moveToFinalFile(extension: String, tmpFile: File, query: ImageSearchQuery): Task[ImageInFile] = Task.delay {
      val destFileName = cacheDir.resolve(query.fileName(extension))
      val destFile = destFileName.toFile
      // If the destination file is present don't overwrite
      if (!destFile.exists()) {
        val finalFile = Files.move(tmpFile.toPath, cacheDir.resolve(destFileName), StandardCopyOption.ATOMIC_MOVE)
        ImageInFile(query, finalFile, finalFile.toFile.length())
      } else {
        tmpFile.delete()
        ImageInFile(query, destFileName, destFile.length())
      }
    }

    for {
      tempFile    <- createTmpFile
      desc        <- openConnection
      _           <- writeToTempFile(tempFile)
      queryHeader <- parseHeader(desc, tempFile)
      file        <- moveToFinalFile(desc.extension, tempFile, queryHeader)
    } yield file
  }
}

object FitsHeadersParser {
  val RaHeader      = "CRVAL1"
  val DecHeader     = "CRVAL2"
  val RaAxisPixels  = "NAXIS1"
  val DecAxisPixels = "NAXIS2"
  val RaPixelSize   = "CDELT1"
  val DecPixelSize  = "CDELT2"

  def headerValue[A](header: Header, key: String)(implicit clazz: ClassTag[A]): Option[A] =
    clazz match {
      case ClassTag.Int(_)    if header.containsKey(key) => clazz.unapply(header.getIntValue(key))
      case ClassTag.Double(_) if header.containsKey(key) => clazz.unapply(header.getDoubleValue(key))
      case _                                          => none
    }

  /**
    * Attempts to read a fits file header and extract the center coordinates and dimensions
    */
  def parseFitsGeometry(file: File, compressed: Boolean): Throwable \/ (Option[Coordinates], Option[AngularSize]) = {
    \/.fromTryCatchNonFatal {
      val fits = new Fits(new FileInputStream(file), compressed)
      val basicHDU = fits.getHDU(0).getHeader
      val coord =
        for {
          raD  <- basicHDU.containsKey(RaHeader) option basicHDU.getDoubleValue(RaHeader)
          decD <- basicHDU.containsKey(DecHeader) option basicHDU.getDoubleValue(DecHeader)
          ra   <- RightAscension.fromAngle(Angle.fromDegrees(raD)).some
          dec  <- Declination.fromDegrees(decD)
        } yield Coordinates(ra, dec)

      val size =
        for {
          raPix   <- basicHDU.containsKey(RaAxisPixels) option basicHDU.getIntValue(RaAxisPixels)
          decPix  <- basicHDU.containsKey(DecAxisPixels) option basicHDU.getIntValue(DecAxisPixels)
          raSize  <- basicHDU.containsKey(RaPixelSize) option basicHDU.getDoubleValue(RaPixelSize)
          decSize <- basicHDU.containsKey(DecPixelSize) option basicHDU.getDoubleValue(DecPixelSize)
        } yield AngularSize(Angle.fromDegrees(raPix * abs(raSize)), Angle.fromDegrees(decPix * abs(decSize)))

      (coord, size.orElse(AngularSize(ImageCatalog.DefaultImageSize, ImageCatalog.DefaultImageSize).some))
    }
  }
}
