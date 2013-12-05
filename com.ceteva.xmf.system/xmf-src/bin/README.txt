Batch files in this folder:

  o compiler.bat
  
    Starts the compiler image. This starts the VM with a 
    large heap and all the definitions loaded in.
    Comtains the top-level command loop.
    
  o makecompiler.bat
  
    Used to create the compiler image. Images are held in ../xmf-img.
    
  o makexmf.bat
  
    Used to make the minimal XCore image. Images are held in ../xmf-img.
    
  o xmf.bat
  
    Starts the minimal XCore image with a very small heap.