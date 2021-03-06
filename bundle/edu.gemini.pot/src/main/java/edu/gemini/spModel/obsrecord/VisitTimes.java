//
// $Id: VisitTimes.java 7011 2006-05-04 16:12:21Z shane $
//

package edu.gemini.spModel.obsrecord;

import edu.gemini.spModel.time.ObsTimeCharge;
import edu.gemini.spModel.time.ChargeClass;
import edu.gemini.spModel.time.ObsTimeCharges;

import java.io.Serializable;

/**
 * VisitTimes is an internal (package private) class used in returning the
 * time required to execute a particular visit.  Contains a long for all the
 * time that was spent outside of executing a dataset, and an array of
 * ObsTimeCharge containing the total times obtaining datasets. The dataset
 * times are split among the charge classes associated with the charge class
 * of the observe iterator that produced the data.
 */
final class VisitTimes implements Serializable {

    private long _unclassifiedTime;
    private long[] _classifiedTimes = new long[ChargeClass.values().length];

    long getUnclassifiedTime() {
        return _unclassifiedTime;
    }

    void addUnclassifiedTime(long time) {
        _unclassifiedTime += time;
    }

    ObsTimeCharge[] getClassifiedTimes() {
        ChargeClass[] allChargeClasses = ChargeClass.values();
        ObsTimeCharge[] charges = new ObsTimeCharge[allChargeClasses.length];
        for (int i=0; i<allChargeClasses.length; ++i) {
            charges[i] = new ObsTimeCharge(_classifiedTimes[i], allChargeClasses[i]);
        }
        return charges;
    }

    void addClassifiedTime(ChargeClass cclass, long time) {
        _classifiedTimes[cclass.ordinal()] += time;
    }

    void addVisitTimes(VisitTimes vt) {
        _unclassifiedTime += vt._unclassifiedTime;
        for (int i=0; i<_classifiedTimes.length; ++i) {
            _classifiedTimes[i] += vt._classifiedTimes[i];
        }
    }

    ObsTimeCharges getTimeCharges(ChargeClass mainChargeClass) {
        ObsTimeCharge[] charges = getClassifiedTimes();
        int index = mainChargeClass.ordinal();
        charges[index] = charges[index].addTime(_unclassifiedTime);
        return new ObsTimeCharges(charges);
    }
}
