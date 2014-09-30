To compile all .xmf files, do the following ONCE:

- Start XModeler, "Open File Browser...", choose xmf-src/

- Right-click the top-most Manifest.xmf -> Manifest / Compile

- Right-click the top-most Manifest.xmf -> Manifest / Build

- Compile the following files individually (right-click / Compile File):
Boot/BootMosaic.xmf
Boot/SystemProjects.xmf
Clients/BootClients.xmf
Tools/Basic/RegistryContributions.xmf
Tools/DiagramTools/Structure/RegistryContributions.xmf

- Manually traverse the ENTIRE tree under Clients/ (except Clients/OldTools
and Clients/XML), and compile EVERY Boot.xmf individually. If you miss a file
here, you will be notified later by an error message of bin/makemosaic, and
can then redo the missing files. Attention: This takes some time!

- After having compiled all files, refresh the project com.ceteva.xmf.system
project in Eclipse (select it and hit F5) to see the newly created .o files.
They are invisible to git due to the .gitignore configuration.   

- To build new system images in xmf-img/, invoke "bin/makexmf ." and 
"bin/makemosaic ." from inside xmf-src/.
