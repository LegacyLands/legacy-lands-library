<div align="center">
    <img src="./logo.png">
    <br /><br />
    <a href="https://app.codacy.com/gh/LegacyLands/legacy-lands-library/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/cccd526f9bc94aaabc990dd65920cd21"/></a>
    <a><img alt="Issues" src="https://img.shields.io/github/issues/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Stars" src="https://img.shields.io/github/stars/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Forks" src="https://img.shields.io/github/forks/LegacyLands/legacy-lands-library"></a>
    <a><img alt="License" src="https://img.shields.io/github/license/LegacyLands/legacy-lands-library"></a>
    <br /><br />
    <p>Based on <a href="https://github.com/FairyProject/fairy" target="_blank">Fairy Framework</a>, it runs as a plug-in, aiming to encapsulate various existing libraries to simplify the development of <a href="https://github.com/PaperMC/Folia" target="_blank">Folia</a> plug-ins.</p>
</div>

## overview

The overall dependence is on the [Fairy Framework](https://github.com/FairyProject/fairy). It probably doesn't have a lot of overly complex stuff, like an unnecessary repackaging of some large library.

## usage

In the compressed package downloaded by [Actions](https://github.com/LegacyLands/legacy-lands-library/actions), `-javadoc` is the javadoc build, `-plugin` only has the compiled class files, and `-sources` has not only the compiled class files but also the source code.

The usage of a particular module is described in detail in the module's `README.md`.

It should be noted that the entire library fully depends on [Fairy Framework](https://github.com/FairyProject/fairy), which will completely simplify our development process and provide various functions. It also depends on [fairy-lib-plugin](https://github.com/FairyProject/fairy-lib-plugin).

We recommend that developers import `-sources` to view javadoc more conveniently in IDA.

To run the module as a plugin (which is the recommended way), run the `-plugin` file directly as a plugin.

**_You need to be careful about dependencies between modules!_**

## modules

- [annotation](annotation/README.md)
- [commons](commons/README.md)
- [configuration](configuration/README.md)
- [mongodb](mongodb/README.md)
- [cache](cache/README.md)
- security          - Not started yet

## sponsors

The project is fully sponsored and maintained by [LegacyLands](https://github.com/LegacyLands).

![legacy-lands-logo.png](./legacy-lands-logo.png)

## star history

We are honored that this library can help more people!

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

## related tutorials (simplified Chinese)

Currently, the production of [Fairy Framework](https://github.com/FairyProject/fairy) and related tutorial videos is being planned and carried out. We will post the detailed video on the bilibili [LegacyLands official account](https://space.bilibili.com/1253128469).
