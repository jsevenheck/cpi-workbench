# Third-party notices

CPI Workbench is licensed under the Apache License 2.0 ([`Apache-2.0.txt`](Apache-2.0.txt)). The
installers and the engine jars include the components below. This folder holds their licence texts
and notices as their projects publish them. It is copied into every engine jar
(`META-INF/third-party/`) and into the installers (`licenses/`).

| Component | Version | Licence | Shipped in | Notices |
|---|---|---|---|---|
| Saxon-HE (Saxonica) | 9.9.1-8 | MPL-2.0, parts under other open-source licences | XML engine 9.9 (jar and native) | [`saxon-he-9.9/`](saxon-he-9.9/) |
| Saxon-HE (Saxonica) | 12.9 | MPL-2.0, parts under other open-source licences | XML engine 12 (jar and native) | [`saxon-he-12/`](saxon-he-12/) |
| xmlresolver | 5.3.3 | Apache-2.0 | XML engine 12 | [`Apache-2.0.txt`](Apache-2.0.txt) |
| Apache Groovy | 2.4.21 | Apache-2.0 (bundles ANTLR 2, ASM, JLine 2 under BSD-style licences) | Groovy engine 1.x | [`groovy-2.4/`](groovy-2.4/) |
| Apache Groovy | 4.0.29 | Apache-2.0 (bundles ANTLR 4, ASM under BSD-style licences) | Groovy engine 2.x | [`groovy-4/`](groovy-4/) |
| Apache Camel | 2.24.3 | Apache-2.0 | Groovy engine 1.x | [`camel-2.24/`](camel-2.24/) |
| Apache Camel | 3.14.7 | Apache-2.0 | Groovy engine 2.x | [`camel-3.14/`](camel-3.14/) |
| JavaBeans Activation (`com.sun.activation`) | 1.2.0 | CDDL-1.1 or GPL-2.0 with Classpath Exception | Groovy engines | [`jakarta-activation-jaxb/`](jakarta-activation-jaxb/) |
| JAXB (`com.sun.xml.bind`) | 2.3.0 | CDDL-1.1 or GPL-2.0 with Classpath Exception | Groovy engine 1.x | [`jakarta-activation-jaxb/`](jakarta-activation-jaxb/) |
| SLF4J | 1.7.26 / 1.7.36 | MIT | Groovy engines | [`slf4j/`](slf4j/) |
| OpenJDK class library | 17 (JRE), 21 (native images) | GPL-2.0 with Classpath Exception | installers: bundled JRE and native XML engines | [`openjdk/`](openjdk/); the bundled JRE also has its own `legal/` folder |
| GraalVM Native Image runtime (Community Edition) | 21.0.2 | GPL-2.0 with Classpath Exception | native XML engines | [`graalvm/`](graalvm/) |

The desktop shell (Tauri, Rust crates) and the UI (Svelte, Monaco Editor and other npm packages)
are listed with their licence texts in `THIRD-PARTY-LICENSES-desktop.txt`, which
`app/scripts/third-party-licenses.mjs` generates when the installers are built.

## Source code

The licences above let you obtain the source code of these components from their projects:

- Saxon-HE 12: <https://github.com/Saxonica/Saxon-HE> (folder `12/source`); Saxon-HE 9.9:
  <https://sourceforge.net/projects/saxon/files/Saxon-HE/9.9/>. Both are also on Maven Central as
  `net.sf.saxon:Saxon-HE` source jars.
- Apache Groovy, Apache Camel, xmlresolver, SLF4J, JavaBeans Activation and JAXB: the source jars on
  Maven Central for the versions above.
- OpenJDK: <https://github.com/adoptium/jdk17u> (the JRE comes from Eclipse Temurin 17) and
  <https://github.com/openjdk/jdk21u>.
- GraalVM Community Edition: <https://github.com/oracle/graal> (tag `jdk-21.0.2`).

CPI Workbench does not modify these components.
