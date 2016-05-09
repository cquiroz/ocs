package jsky.app.ot.gemini.editor;

import edu.gemini.pot.sp.ISPObsComponent;
import edu.gemini.spModel.obscomp.SPInstObsComp;
import jsky.app.ot.editor.OtItemEditor;
import jsky.util.gui.TextBoxWidget;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;


/**
 * The base class for all instrument components.  It includes support for
 * a rotation angle text box, something all instruments support.
 *
 * NOTE: We do not use the getPosAngleTextBox mechanism any more with instrument editors that support the
 * parallactic angle feature, because the logic for this is fully contained in PositionAnglePanel.
 * Thus, this method should return null if a PositionAnglePanel is used.
 */
public abstract class EdCompInstBase<T extends SPInstObsComp> extends OtItemEditor<ISPObsComponent, T>
        implements PropertyChangeListener, jsky.util.gui.TextBoxWidgetWatcher {

    /** If true, ignore property change events */
    private boolean _ignoreChanges = false;

    @Override
    protected void cleanup() {
        if (getDataObject() != null) {
            getDataObject().removePropertyChangeListener(this);
            if (getPosAngleTextBox() != null) getPosAngleTextBox().deleteWatcher(this);
            if (getExposureTimeTextBox() != null) getExposureTimeTextBox().deleteWatcher(this);
            if (getCoaddsTextBox() != null) getCoaddsTextBox().deleteWatcher(this);
        }
    }

    @Override protected void init() {
        if (getDataObject() != null) {
            getDataObject().addPropertyChangeListener(this);

            if (getPosAngleTextBox() != null) {
                getPosAngleTextBox().setText(getDataObject().getPosAngleDegreesStr());
                getPosAngleTextBox().addWatcher(this);

                // We now ignore changes to the pos angle if the text field is being edited.
                // As a result, make sure everything is synched when editing starts / stops.
                getPosAngleTextBox().addFocusListener(new FocusAdapter() {
                    @Override public void focusLost(final FocusEvent e) {
                        updatePosAngle(false);
                    }
                    @Override public void focusGained(final FocusEvent e) {
                        updatePosAngle(false);
                    }
                });
                getPosAngleTextBox().addKeyListener(new KeyAdapter() {
                    @Override public void keyPressed(final KeyEvent e) {
                        super.keyPressed(e);
                        if (e.getKeyCode() == KeyEvent.VK_ENTER)
                            updatePosAngle(true);
                    }
                });
            }

            if (getExposureTimeTextBox() != null) {
                getExposureTimeTextBox().setText(getDataObject().getExposureTimeAsString());
                getExposureTimeTextBox().addWatcher(this);
            }

            if (getCoaddsTextBox() != null) {
                getCoaddsTextBox().setText(getDataObject().getCoaddsAsString());
                getCoaddsTextBox().addWatcher(this);
            }
        }
    }

    /**
     * A key was pressed in the given TextBoxWidget.
     */
    public void textBoxKeyPress(final TextBoxWidget tbwe) {
        if (getDataObject() != null) {
            _ignoreChanges = true;
            try {
                if (tbwe == getPosAngleTextBox()) {
                    final double defaultPositionAngle = 0.0;
                    getDataObject().setPosAngleDegrees(tbwe.getDoubleValue(defaultPositionAngle));
                } else if (tbwe == getExposureTimeTextBox()) {
                    final double expTime    = tbwe.getDoubleValue(getDefaultExposureTime());
                    getDataObject().setExposureTime(expTime);
                } else if (tbwe == getCoaddsTextBox()) {
                    final int defaultCoadds = 1;
                    getDataObject().setCoadds(tbwe.getIntegerValue(defaultCoadds));
                }
            } finally {
                _ignoreChanges = false;
            }
        }
    }

    // Copy the data model pos angle value to the pos angle text field.
    private void updatePosAngle(final boolean ignoreFocus) {
        if (!_ignoreChanges) {
            // Ignore model changes to the pos angle if the pos angle text box has the focus.
            // This is to avoid changing the text box value when BAGS selects an auto group at +180.
            final TextBoxWidget posAngleTextBox = getPosAngleTextBox();
            if (posAngleTextBox != null && getDataObject() != null && (ignoreFocus || !posAngleTextBox.hasFocus())) {
                final String newAngle = getDataObject().getPosAngleDegreesStr();
                if (!newAngle.equals(posAngleTextBox.getText())) {
                    posAngleTextBox.setText(newAngle);
                }
            }
        }
    }

    // Copy the data model exposure time value to the exposure time text field.
    private void updateExpTime() {
        if (!_ignoreChanges) {
            final TextBoxWidget expTimeTextBox = getExposureTimeTextBox();
            if (expTimeTextBox != null && getDataObject() != null) {
                final String newExpTime = getDataObject().getExposureTimeAsString();
                if (!newExpTime.equals(expTimeTextBox.getText())) {
                    expTimeTextBox.setText(newExpTime);
                }
            }
        }
    }

    public void propertyChange(final PropertyChangeEvent evt) {
        updatePosAngle(false);
        updateExpTime();
    }

    /**
     * Return the position angle text box
     * Providing null is a temporary measure that indicates that external code handles the position angle.
     * This will go away when all position angle handling for editors is done through the PositionAnglePanel.
     * TODO: Remove this and all references.
     */
    public TextBoxWidget getPosAngleTextBox() { return null; }

    /** Return the exposure time text box */
    public abstract TextBoxWidget getExposureTimeTextBox();

    /** Return the coadds text box, or null if not available. */
    public abstract TextBoxWidget getCoaddsTextBox();

    /** The default exposure time. Subclasses can override. **/
    protected double getDefaultExposureTime() {
        return 60.0;
    }
}

