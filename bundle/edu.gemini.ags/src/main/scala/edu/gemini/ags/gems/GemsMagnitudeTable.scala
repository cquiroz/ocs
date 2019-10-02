package edu.gemini.ags.gems

import edu.gemini.ags.api.AgsMagnitude.{MagnitudeCalc, MagnitudeTable}
import edu.gemini.pot.ModelConverters._
import edu.gemini.catalog.api._
import edu.gemini.spModel.core._
import edu.gemini.spModel.gemini.gems.CanopusWfs
import edu.gemini.spModel.gemini.gsaoi.{Gsaoi, GsaoiOdgw}
import edu.gemini.spModel.gems.GemsGuideStarType
import edu.gemini.spModel.guide.{GuideProbe, GuideSpeed}
import edu.gemini.spModel.obs.context.ObsContext
import edu.gemini.spModel.rich.shared.immutable._
import edu.gemini.spModel.gemini.obscomp.SPSiteQuality.{Conditions, ImageQuality, SkyBackground}
import edu.gemini.skycalc

import scala.collection.JavaConverters._
import scalaz._
import Scalaz._

/**
 * A magnitude table defined in the same way as we have done since before 2015A
 * (that is, with predefined magnitude limits).
 */
object GemsMagnitudeTable extends MagnitudeTable {
  /**
    * Normally, each type of condition (CC, IQ, SB) affect magnitude limits:
    * 1. independently (e.g. the CC's affect on mag limits does not depend on the SB); and
    * 2. uniformly (e.g. if CC changed the faintness limit, it also changed the saturation limit by the same amount).
    *
    * In NGS2, however, this is no longer the case:
    * 1. The value of one condition can affect another's effect on magnitude limits.
    * 2. For a given set of conditions, they might affect faintness and saturation limits differently.
    *
    * Thus, to create the magnitude limits adjuster for NGS2, we need to use some customization as per the following
    * functions:
    */
    val NGS2ConditionsAdjuster: ConstraintsAdjuster[Conditions] = {
      def faintnessAdjuster(c: Conditions, bands: BandsList): Double = {
        (c.iq, c.sb, bands) match {
          case (ImageQuality.ANY,        SkyBackground.ANY,        RBandsList) => -0.3
          case (ImageQuality.ANY,        SkyBackground.PERCENT_80, RBandsList) => -0.2
          case (ImageQuality.ANY,        SkyBackground.PERCENT_50, RBandsList) => -0.5
          case (ImageQuality.ANY,        SkyBackground.PERCENT_20, RBandsList) => -0.5
          case (ImageQuality.PERCENT_85, SkyBackground.ANY,        RBandsList) => -0.5
          case _                                                               =>  0.0
        }
      }

      def brightnessAdjuster(c: Conditions, bands: BandsList): Double = {
        (c.iq, c.sb, bands) match {
          case (ImageQuality.ANY, SkyBackground.ANY,        RBandsList) => 0.2
          case (ImageQuality.ANY, SkyBackground.PERCENT_80, RBandsList) => 0.3
          case _                                                        => 0.0
        }
      }

      ConstraintsAdjuster.customConstraintsAdjuster(faintnessAdjuster, brightnessAdjuster)
    }

  val CwfsAdjust: MagnitudeConstraints => Conditions => MagnitudeConstraints = {
    mc => conds => NGS2ConditionsAdjuster.adjust(conds, conds.sb.adjust(conds.cc.adjust(conds.iq.adjust(mc))))
  }

  val OtherAdjust: MagnitudeConstraints => Conditions => MagnitudeConstraints =
    mc => conds => conds.adjust(mc)

  private def magLimits(bands: BandsList, fl: Double, sl: Double): MagnitudeConstraints =
    MagnitudeConstraints(bands, FaintnessConstraint(fl), SaturationConstraint(sl).some)

  def apply(ctx: ObsContext, probe: GuideProbe): Option[MagnitudeCalc] = {

    def magCalc(f: Conditions => MagnitudeConstraints): MagnitudeCalc =
      new MagnitudeCalc() {
        def apply(conds: Conditions, speed: GuideSpeed): MagnitudeConstraints =
          f(conds)
      }

    val cwfsCalc: MagnitudeConstraints => MagnitudeCalc = mc =>
      magCalc(CwfsAdjust(mc))

    val odgwCalc: MagnitudeConstraints => MagnitudeCalc = mc =>
      magCalc(OtherAdjust(mc))

    def lookup(site: Site): Option[MagnitudeCalc] =
      (site, probe) match {
        case (Site.GS, odgw: GsaoiOdgw)  =>
          Some(odgwCalc(GsaoiOdgwMagnitudeLimitsCalculator.gemsMagnitudeConstraint(GemsGuideStarType.flexure, MagnitudeBand.H.some)))

        case (Site.GS, can: CanopusWfs) =>
          Some(cwfsCalc(CanopusWfsMagnitudeLimitsCalculator.gemsMagnitudeConstraint(GemsGuideStarType.tiptilt, MagnitudeBand.R.some)))

        case _                           =>
          None
      }

    ctx.getSite.asScalaOpt.flatMap(lookup)
  }

  /**
   * GSAOI, Canopus, and F2 require special handling for magnitude limits for GeMS.
   */
  trait LimitsCalculator {
    def gemsMagnitudeConstraint(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand]): MagnitudeConstraints

    def adjustGemsMagnitudeConstraintForJava(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand], conditions: Conditions): MagnitudeConstraints
  }

  lazy val GsaoiOdgwMagnitudeLimitsCalculator = new LimitsCalculator {
    /**
     * The map formerly in Gsaoi.Filter.
     */
    private val MagnitudeLimitsMap = Map[Tuple2[GemsGuideStarType, BandsList], MagnitudeConstraints](
      (GemsGuideStarType.flexure, SingleBand(MagnitudeBand.J)) -> magLimits(SingleBand(MagnitudeBand.J), 17.2, 8.0),
      (GemsGuideStarType.flexure, SingleBand(MagnitudeBand.H)) -> magLimits(SingleBand(MagnitudeBand.H), 17.0, 8.0),
      (GemsGuideStarType.flexure, SingleBand(MagnitudeBand.K)) -> magLimits(SingleBand(MagnitudeBand.K), 18.2, 8.0),
      (GemsGuideStarType.tiptilt, SingleBand(MagnitudeBand.J)) -> magLimits(SingleBand(MagnitudeBand.J), 14.2, 7.1),
      (GemsGuideStarType.tiptilt, SingleBand(MagnitudeBand.H)) -> magLimits(SingleBand(MagnitudeBand.H), 14.5, 7.3),
      (GemsGuideStarType.tiptilt, SingleBand(MagnitudeBand.K)) -> magLimits(SingleBand(MagnitudeBand.K), 13.5, 6.5)
    )

    override def adjustGemsMagnitudeConstraintForJava(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand], conditions: Conditions): MagnitudeConstraints =
      OtherAdjust(gemsMagnitudeConstraint(starType, nirBand))(conditions)

    override def gemsMagnitudeConstraint(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand]): MagnitudeConstraints= {
      val filter = nirBand.fold(Gsaoi.Filter.H)(band => Gsaoi.Filter.getFilter(band, Gsaoi.Filter.H))
      MagnitudeLimitsMap((starType, filter.getCatalogBand.getValue))
    }
  }

  /**
   * Since Canopus is not explicitly listed in GemsInstrument, it must be visible outside of the table in order to
   * be used directly by Mascot, since it cannot be looked up through the GemsInstrumentToMagnitudeLimitsCalculator map.
   */
  trait CanopusWfsCalculator extends LimitsCalculator {
    def getNominalMagnitudeConstraints(cwfs: CanopusWfs): MagnitudeConstraints
  }

  // For commissioning we were asked to go 1.5 mag fainter.  Leaving this here
  // to make it easy to remove or adjust when the actual magnitude limits are
  // known.
  val CommissioningAdj = 1.5

  lazy val CanopusWfsMagnitudeLimitsCalculator = new CanopusWfsCalculator {
    override def adjustGemsMagnitudeConstraintForJava(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand], conditions: Conditions): MagnitudeConstraints =
      CwfsAdjust(gemsMagnitudeConstraint(starType, nirBand))(conditions)

    // These values correspond to:
    // CC = 50, IQ = 70, SB = 50 or 20
    // CC = 50, IQ = 20, SB = ANY

    private val NominalFaintLimit = 16.5
    private val FaintLimit        = NominalFaintLimit + CommissioningAdj
    private val BrightLimit       = 10.0

    override def gemsMagnitudeConstraint(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand]): MagnitudeConstraints =
      magLimits(RBandsList, FaintLimit, BrightLimit)

    override def getNominalMagnitudeConstraints(cwfs: CanopusWfs): MagnitudeConstraints =
      magLimits(RBandsList, FaintLimit, BrightLimit)
  }

  private lazy val Flamingos2OiwfsMagnitudeLimitsCalculator = new LimitsCalculator {
    override def adjustGemsMagnitudeConstraintForJava(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand], conditions: Conditions): MagnitudeConstraints =
      OtherAdjust(gemsMagnitudeConstraint(starType, nirBand))(conditions)

    override def gemsMagnitudeConstraint(starType: GemsGuideStarType, nirBand: Option[MagnitudeBand]) =
      magLimits(RBandsList, 18.0, 9.5)
  }
}