// --- Plugin versions pinned in one place ---
val scalafmtVersion      = "2.5.2"
val sbtNativePackagerVersion = "1.10.4"
val sbtAssemblyVersion   = "2.3.0"

addSbtPlugin("org.scalameta"    % "sbt-scalafmt"        % scalafmtVersion)
addSbtPlugin("com.github.sbt"   % "sbt-native-packager" % sbtNativePackagerVersion)
addSbtPlugin("com.eed3si9n"     % "sbt-assembly"        % sbtAssemblyVersion)
