# This is the new verion of DanyR, which is adapted from Dafny (4/23/2019). It is still at the stage of active development.

# DafnyR
DafnyR is an experimental tool for sequential program specification and verification. It is a variant of Dafny and is inspired by region logic. DafnyR is built on a fine-grained region logic and allows one to use several styles of specifying the frame properties in sequential programs: dynamic frames, region logic and separation logic.

# Setup Source Code (Following the instructions from [Dafny](https://github.com/Microsoft/dafny/wiki/INSTALL))

## Windows

1. install the following external dependencies:
    * [Visual Studio](https://visualstudio.microsoft.com/downloads/)
    * [Visual Studio sdk extension](https://docs.microsoft.com/en-us/visualstudio/extensibility/installing-the-visual-studio-sdk?view=vs-2019)
    * [Code contract extension](https://marketplace.visualstudio.com/items?itemName=RiSEResearchinSoftwareEngineering.CodeContractsforNET)
    * [NUnit test adapter](https://marketplace.visualstudio.com/items?itemName=NUnitDevelopers.NUnit3TestAdapter)
    * To install lit (for test run):
      1. install python
      2. install pip
      3. run "pip install lit" and "pip install OutputCheck"
2. clone source code:
    * DanyR
    * [Boogie](https://github.com/boogie-org/boogie)
    * [BoogiePartners](https://github.com/boogie-org/boogie-partners)
    * copy [Coco.exe](http://www.ssw.uni-linz.ac.at/Research/Projects/Coco/) to \boogiepartners\CocoRdownload
3. build the following project in the following order:
    1. boogie\Source\Boogie.sln
    2. dafnyR\Source\DafnyR.sln
    3. dafnyR\Source\DafnyExtension.sln
 4. following the convensions;
    * Set "General:Tab" to "2 2"
    * For "C#:Formmating:NewLines Turn everything off except the first option.
