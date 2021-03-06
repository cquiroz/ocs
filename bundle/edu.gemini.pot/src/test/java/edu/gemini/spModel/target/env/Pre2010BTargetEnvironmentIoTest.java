package edu.gemini.spModel.target.env;

import edu.gemini.shared.util.immutable.ImList;
import edu.gemini.shared.util.immutable.Option;
import edu.gemini.shared.util.immutable.Some;
import edu.gemini.spModel.pio.ParamSet;
import edu.gemini.spModel.pio.PioFactory;
import edu.gemini.spModel.pio.xml.PioXmlFactory;
import edu.gemini.spModel.pio.xml.PioXmlUtil;
import edu.gemini.spModel.target.SPTarget;
import edu.gemini.spModel.target.obsComp.PwfsGuideProbe;
import static org.junit.Assert.*;
import org.junit.Test;

import java.io.File;
import java.net.URL;

/**
 * Tests parsing pre-2010B science programs.
 */
public class Pre2010BTargetEnvironmentIoTest {

    private static ParamSet load(String name) throws Exception {
        URL url = Pre2010BTargetEnvironmentIoTest.class.getResource(name);
        File f = new File(url.toURI());
        return (ParamSet) PioXmlUtil.read(f);
    }

    private static TargetEnvironment parse(String name) throws Exception {
        return TargetEnvironment.fromParamSet(load(name));
    }

    // We're not trying to verify the entire SPTarget parsing code.  It's
    // enough to know that the name is what we expected.
    private static void verifyTarget(String name, SPTarget target) throws Exception {
        assertEquals(name, target.getName());
    }

    private enum Case {
        BASE_ONLY("IoBaseOnly") {
            public void verify(TargetEnvironment env) throws Exception {
                verifyTarget("BaseName", env.getBase());
                assertEquals(0, env.getUserTargets().size());

                // An initial disabled auto group and a manual primary group
                final ImList<GuideGroup> groups = env.getGuideEnvironment().getOptions();
                assertEquals(2, groups.size());
                assertTrue(groups.get(0).grp() instanceof AutomaticGroup.Disabled$);
                assertTrue(groups.get(1).grp() instanceof ManualGroup);
                assertTrue(1 == env.getGuideEnvironment().getPrimaryIndex());
            }
        },

        ONE_GUIDE_PROBE_TARGET("IoOneGuideProbeTarget") {
            public void verify(TargetEnvironment env) throws Exception {
                verifyTarget("BaseName", env.getBase());
                assertEquals(0, env.getUserTargets().size());

                GuideEnvironment genv = env.getGuideEnvironment();

                // There are two guide groups
                assertEquals(2, genv.getOptions().size());

                // The primary defaults to the manual group.
                GuideGroup grp = genv.getPrimary();
                assertEquals(genv.getOptions().tail().head(), grp);

                // It contains a single GuideProbeTargets instance
                assertEquals(1, grp.getAll().size());

                Option<GuideProbeTargets> gptOpt = grp.get(PwfsGuideProbe.pwfs2);
                GuideProbeTargets gpt = gptOpt.getValue();

                // Which contains a single target
                assertEquals(1, gpt.getTargets().size());

                SPTarget primary = gpt.getPrimary().getValue();
                verifyTarget("GSC001", primary);
            }
        },

        TWO_GUIDE_PROBE_TARGET("IoTwoGuideProbeTarget") {
            public void verify(TargetEnvironment env) throws Exception {
                GuideEnvironment genv = env.getGuideEnvironment();

                GuideGroup grp = genv.getPrimary();
                Option<GuideProbeTargets> gpt1Opt = grp.get(PwfsGuideProbe.pwfs1);
                Option<GuideProbeTargets> gpt2Opt = grp.get(PwfsGuideProbe.pwfs2);

                SPTarget primary1 = gpt1Opt.getValue().getPrimary().getValue();
                verifyTarget("GSC001", primary1);

                SPTarget primary2 = gpt2Opt.getValue().getPrimary().getValue();
                verifyTarget("GSC002", primary2);
            }
        },

        DISABLED_GUIDE_PROBE_TARGET("IoDisabledGuideProbeTarget") {
            public void verify(TargetEnvironment env) throws Exception {
                GuideEnvironment genv = env.getGuideEnvironment();

                GuideGroup grp = genv.getPrimary();
                Option<GuideProbeTargets> gptOpt = grp.get(PwfsGuideProbe.pwfs2);

                SPTarget primary = gptOpt.getValue().getPrimary().getValue();
                verifyTarget("GSC001", primary);
            }
        }
        ;

        private final String name;
        Case(String name) { this.name = name; }
        public String fileName() { return name + ".xml"; }

        public abstract void verify(TargetEnvironment env) throws Exception;
    }

    @Test
    public void testIO() throws Exception {
        PioFactory fact = new PioXmlFactory();
        for (Case c : Case.values()) {
            try {
                TargetEnvironment env = parse(c.fileName());
                c.verify(env);
                c.verify(TargetEnvironment.fromParamSet(env.getParamSet(fact)));
            } catch (Exception ex) {
                System.err.println("Failed " + c.fileName());
                throw ex;
            }
        }
    }
}
