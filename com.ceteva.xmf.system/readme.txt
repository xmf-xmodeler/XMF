To compile all .xmf files, do the following ONCE:

- Start XModeler, "Open File Browser...", choose xmf-src/

- Right-click the top-most Manifest.xmf -> Compile File

- Right-click the top-most Manifest.xmf -> Manifest / Build

- Refresh the project com.ceteva.xmf.system in Eclipse (select and hit F5) to
see the newly created .o files. They will be local files and not part of the
repository due to the .gitignore configuration.   

- To build new system images in xmf-img/, invoke "bin/makexmf ." and 
"bin/makemosaic ." from inside xmf-src/.
