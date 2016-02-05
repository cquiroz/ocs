import OcsKeys._

// note: inter-project dependencies are declared at the top, in projects.sbt

name := "edu.gemini.auxfile.workflow"

// version set in ThisBuild

unmanagedJars in Compile ++= Seq(
  new File(baseDirectory.value, "../../lib/bundle/osgi.cmpn-4.3.1.jar")
  // new File(baseDirectory.value, "../../lib/bundle/org.apache.felix-4.2.1.jar"),
  // new File(baseDirectory.value, "../../lib/bundle/org.apache.felix.http.jetty-2.2.0.jar"),
  // new File(baseDirectory.value, "../../lib/bundle/scala-library-2.10.5.jar"),
  // new File(baseDirectory.value, "../../lib/bundle/pax-web-jetty-bundle-1.1.13.jar"))
)

osgiSettings

ocsBundleSettings

OsgiKeys.bundleActivator := Some("edu.gemini.auxfile.osgi.Activator")

OsgiKeys.bundleSymbolicName := name.value

OsgiKeys.dynamicImportPackage := Seq("")

OsgiKeys.privatePackage := Seq(
  "edu.gemini.auxfile.*"
)

OsgiKeys.exportPackage := Seq(
  "edu.gemini.auxfile.api",
  "edu.gemini.auxfile.client",
  "edu.gemini.auxfile.server",
  "edu.gemini.auxfile.copier")


