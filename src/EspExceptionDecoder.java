/*
  Copyright (c) 2015 Hristo Gochkov (ficeto at ficeto dot com)

  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/
package com.ficeto.esp;
import cc.arduino.files.DeleteFilesOnShutdown;
import java.awt.*;
import java.awt.event.*;
import java.awt.event.ActionEvent;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.regex.*;
import javax.swing.*;
import javax.swing.filechooser.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import org.apache.commons.codec.digest.DigestUtils;
import processing.app.Base;
import processing.app.BaseNoGui;
import processing.app.Editor;
import processing.app.Platform;
import processing.app.PreferencesData;
import processing.app.Sketch;
//import processing.app.SketchData;
import processing.app.debug.TargetPlatform;
import processing.app.helpers.FileUtils;
import processing.app.helpers.ProcessUtils;
import processing.app.tools.Tool;

public class EspExceptionDecoder implements Tool, DocumentListener {
  Editor editor;
  JTextPane outputArea;
  String outputText;
  JTextArea inputArea;
  JFrame frame;
  File tool;
  File elf;

  private static String[] exceptions = {
    "Illegal instruction",
    "SYSCALL instruction",
    "InstructionFetchError: Processor internal physical address or data error during instruction fetch",
    "LoadStoreError: Processor internal physical address or data error during load or store",
    "Level1Interrupt: Level-1 interrupt as indicated by set level-1 bits in the INTERRUPT register",
    "Alloca: MOVSP instruction, if caller's registers are not in the register file",
    "IntegerDivideByZero: QUOS, QUOU, REMS, or REMU divisor operand is zero",
    "reserved",
    "Privileged: Attempt to execute a privileged operation when CRING ? 0",
    "LoadStoreAlignmentCause: Load or store to an unaligned address",
    "reserved",
    "reserved",
    "InstrPIFDataError: PIF data error during instruction fetch",
    "LoadStorePIFDataError: Synchronous PIF data error during LoadStore access",
    "InstrPIFAddrError: PIF address error during instruction fetch",
    "LoadStorePIFAddrError: Synchronous PIF address error during LoadStore access",
    "InstTLBMiss: Error during Instruction TLB refill",
    "InstTLBMultiHit: Multiple instruction TLB entries matched",
    "InstFetchPrivilege: An instruction fetch referenced a virtual address at a ring level less than CRING",
    "reserved",
    "InstFetchProhibited: An instruction fetch referenced a page mapped with an attribute that does not permit instruction fetch",
    "reserved",
    "reserved",
    "reserved",
    "LoadStoreTLBMiss: Error during TLB refill for a load or store",
    "LoadStoreTLBMultiHit: Multiple TLB entries matched for a load or store",
    "LoadStorePrivilege: A load or store referenced a virtual address at a ring level less than CRING",
    "reserved",
    "LoadProhibited: A load referenced a page mapped with an attribute that does not permit loads",
    "StoreProhibited: A store referenced a page mapped with an attribute that does not permit stores"
  };
  
  public void init(Editor editor) {
    this.editor = editor;
  }

  public String getMenuTitle() {
    return "ESP Exception Decoder";
  }

  private int listenOnProcess(String[] arguments){
    try {
      final Process p = ProcessUtils.exec(arguments);
      Thread thread = new Thread() {
        public void run() {
          try {
            InputStreamReader reader = new InputStreamReader(p.getInputStream());
            int c;
            String line = "";
            while ((c = reader.read()) != -1){
              if((char)c == '\r')
                continue;
              if((char)c == '\n'){
                printLine(line);
                line = "";
              } else {
                line += (char)c;
              }
            }
            printLine(line);
            reader.close();

            reader = new InputStreamReader(p.getErrorStream());
            while ((c = reader.read()) != -1)
                System.err.print((char) c);
            reader.close();
          } catch (Exception e){}
        }
      };
      thread.start();
      int res = p.waitFor();
      thread.join();
      return res;
    } catch (Exception e){}
    return -1;
  }

  private void sysExec(final String[] arguments){
    Thread thread = new Thread() {
      public void run() {
        try {
          if(listenOnProcess(arguments) != 0){
            editor.statusError("Decode Failed");
            outputArea.setText("<html><font color=red>Decode Failed</font></html>");
          } else {
            editor.statusNotice("Decode Success");
            outputArea.setText(outputText);
          }
        } catch (Exception e){
          editor.statusError("Decode Exception");
          outputArea.setText("<html><font color=red><b>Decode Exception:</b> "+e.getMessage()+"</font></html>");
        }
      }
    };
    thread.start();
  }

  private String getBuildFolderPath(Sketch s) {
  // first of all try the getBuildPath() function introduced with IDE 1.6.12
  // see commit arduino/Arduino#fd1541eb47d589f9b9ea7e558018a8cf49bb6d03
  try {
    String buildpath = s.getBuildPath().getAbsolutePath();
    return buildpath;
  }
  catch (IOException er) {
       editor.statusError(er);
  }
  catch (Exception er) {
    try {
        File buildFolder = FileUtils.createTempFolder("build", DigestUtils.md5Hex(s.getMainFilePath()) + ".tmp");
        //DeleteFilesOnShutdown.add(buildFolder);
        return buildFolder.getAbsolutePath();
      }
      catch (IOException e) {
        editor.statusError(e);
      }
      catch (Exception e) {
        // Arduino 1.6.5 doesn't have FileUtils.createTempFolder
        // String buildPath = BaseNoGui.getBuildFolder().getAbsolutePath();
        java.lang.reflect.Method method;
        try {
          method = BaseNoGui.class.getMethod("getBuildFolder");
          File f = (File) method.invoke(null);
          return f.getAbsolutePath();
        } catch (SecurityException ex) {
          editor.statusError(ex);
        } catch (IllegalAccessException ex) {
          editor.statusError(ex);
        } catch (InvocationTargetException ex) {
          editor.statusError(ex);
        } catch (NoSuchMethodException ex) {
          editor.statusError(ex);
        }
      }
  }
    return "";
  }


  private long getIntPref(String name){
    String data = BaseNoGui.getBoardPreferences().get(name);
    if(data == null || data.contentEquals("")) return 0;
    if(data.startsWith("0x")) return Long.parseLong(data.substring(2), 16);
    else return Integer.parseInt(data);
  }

  class ElfFilter extends FileFilter {
    public String getExtension(File f) {
        String ext = null;
        String s = f.getName();
        int i = s.lastIndexOf('.');
        if (i > 0 &&  i < s.length() - 1) {
            ext = s.substring(i+1).toLowerCase();
        }
        return ext;
    }
    public boolean accept(File f) {
        if (f.isDirectory()) {
            return true;
        }
        String extension = getExtension(f);
        if (extension != null) {
            return extension.equals("elf");
        }
        return false;
    }
    public String getDescription() {
        return "*.elf files";
    }
  }

  private void createAndUpload(){
    if(!PreferencesData.get("target_platform").contentEquals("esp8266") && !PreferencesData.get("target_platform").contentEquals("esp32") && !PreferencesData.get("target_platform").contentEquals("ESP31B")){
      System.err.println();
      editor.statusError("Not Supported on "+PreferencesData.get("target_platform"));
      return;
    }

    String tc = "esp32";
    if(PreferencesData.get("target_platform").contentEquals("esp8266")){
      tc = "lx106";
    }

    TargetPlatform platform = BaseNoGui.getTargetPlatform();

    String gccPath = PreferencesData.get("runtime.tools.xtensa-"+tc+"-elf-gcc.path");
    if(gccPath == null){
      gccPath = platform.getFolder() + "/tools/xtensa-"+tc+"-elf";
    }

    String addr2line;
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
      addr2line = "xtensa-"+tc+"-elf-addr2line.exe";
    else
      addr2line = "xtensa-"+tc+"-elf-addr2line";

    tool = new File(gccPath + "/bin", addr2line);
    if (!tool.exists() || !tool.isFile()) {
      System.err.println();
      editor.statusError("ERROR: "+addr2line+" not found!");
      return;
    }

    elf = new File(getBuildFolderPath(editor.getSketch()), editor.getSketch().getName() + ".ino.elf");
    if (!elf.exists() || !elf.isFile()) {
      elf = new File(getBuildFolderPath(editor.getSketch()), editor.getSketch().getName() + ".cpp.elf");
      if (!elf.exists() || !elf.isFile()){
        //lets give the user a chance to select the elf
        final JFileChooser fc = new JFileChooser();
        fc.addChoosableFileFilter(new ElfFilter());
        fc.setAcceptAllFileFilterUsed(false);
        int returnVal = fc.showDialog(editor, "Select ELF");
        if (returnVal == JFileChooser.APPROVE_OPTION) {
          elf = fc.getSelectedFile();
        } else {
          editor.statusError("ERROR: elf was not found!");
          System.err.println("Open command cancelled by user.");
          return;
        }
      }
    }

    JFrame.setDefaultLookAndFeelDecorated(true);
    frame = new JFrame("Exception Decoder");
    frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

    inputArea = new JTextArea("Paste your stack trace here", 16, 60);
    inputArea.setLineWrap(true);
    inputArea.setWrapStyleWord(true);
    inputArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "commit");
    inputArea.getActionMap().put("commit", new CommitAction());
    inputArea.getDocument().addDocumentListener(this);
    frame.getContentPane().add(new JScrollPane(inputArea), BorderLayout.PAGE_START);
    
    outputText = "";
    outputArea = new JTextPane();
    outputArea.setContentType("text/html");
    outputArea.setEditable(false);
    outputArea.setBackground(null);
    outputArea.setBorder(null);
    outputArea.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);
    outputArea.setText(outputText);
    
    JScrollPane outputScrollPane = new JScrollPane(outputArea);
    outputScrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
    outputScrollPane.setPreferredSize(new Dimension(640, 200));
    outputScrollPane.setMinimumSize(new Dimension(10, 10));
    frame.getContentPane().add(outputScrollPane, BorderLayout.CENTER);
    
    frame.pack();
    frame.setVisible(true);
  }

  private void printLine(String line){
    String address = "", method = "", file = "";
    if(line.startsWith("0x")){
      address = line.substring(0, line.indexOf(':'));
      line = line.substring(line.indexOf(':') + 2);
    } else if(line.startsWith("(inlined by)")){
      line = line.substring(13);
      address = "inlined by";
    }
    int atIndex = line.indexOf(" at ");
    if(atIndex == -1)
      return;
    method = line.substring(0, atIndex);
    line = line.substring(atIndex + 4);
    file = line.substring(0, line.lastIndexOf(':'));
    if(file.length() > 0){
      int lastfs = file.lastIndexOf('/');
      int lastbs = file.lastIndexOf('\\');
      int slash = (lastfs > lastbs)?lastfs:lastbs;
      if(slash != -1){
        String filename = file.substring(slash+1);
        file = file.substring(0,slash+1) + "<b>" + filename + "</b>";
      }
    }
    line = line.substring(line.lastIndexOf(':') + 1);
    String html = "" +
      "<font color=green>" + address + ": </font>" +
      "<b><font color=blue>" + method + "</font></b> at " + file + " line <b>" + line + "</b>";
    outputText += html +"\n";
  }

  public void run() {
    createAndUpload();
  }

  private void parseException(){
    String content = inputArea.getText();
    Pattern p = Pattern.compile("Exception \\(([0-9]*)\\):");
    Matcher m = p.matcher(content);
    if(m.find()){
      int exception = Integer.parseInt(m.group(1));
      if(exception < 0 || exception > 29){
        return;
      }
      outputText += "<b><font color=red>Exception "+exception+": "+exceptions[exception]+"</font></b>\n";
    }
  }

  private void parseText(){
    String content = inputArea.getText();
    Pattern p = Pattern.compile("40[0-2](\\d|[a-f]){5}\\b");
    int count = 0;
    Matcher m = p.matcher(content);
    while(m.find()) {
      count ++;
    }
    if(count == 0){
      return;
    }
    String command[] = new String[4+count];
    int i = 0;
    command[i++] = tool.getAbsolutePath();
    command[i++] = "-aipfC";
    command[i++] = "-e";
    command[i++] = elf.getAbsolutePath();
    m = p.matcher(content);
    while(m.find()) {
      command[i++] = content.substring(m.start(), m.end());
    }
    outputText += "<i>Decoding "+count+" results</i>\n";
    sysExec(command);
  }

  private void parseAlloc() {
    String content = inputArea.getText();
    Pattern p = Pattern.compile("last failed alloc call: 40[0-2](\\d|[-A-F]){5}\\((\\d)+\\)");
    Matcher m = p.matcher(content);
    if (m.find()) {
      String fs = content.substring(m.start(), m.end());
      Pattern p2 = Pattern.compile("40[0-2](\\d|[A-F]){5}\\b");
      Matcher m2 = p2.matcher(fs);
      if (m2.find()) {
          String addr = fs.substring(m2.start(), m2.end());
          Pattern p3 = Pattern.compile("\\((\\d)+\\)");
          Matcher m3 = p3.matcher(fs);
          if (m3.find()) {
            String size = fs.substring(m3.start()+1, m3.end()-1);

            String command[] = new String[5];
            command[0] = tool.getAbsolutePath();
            command[1] = "-aipfC";
            command[2] = "-e";
            command[3] = elf.getAbsolutePath();
            command[4] = addr;
      
            try {
              final Process proc = ProcessUtils.exec(command);
              InputStreamReader reader = new InputStreamReader(proc.getInputStream());
              int c;
              String line = "";
              while ((c = reader.read()) != -1){
                if((char)c == '\r')
                  continue;
                if((char)c == '\n' && line != ""){
                  outputText += "Memory allocation of " + size + " bytes failed at " + line + "\n";
                  break;
                } else {
                 line += (char)c;
                }
              }
              reader.close();
            } catch (Exception er) { }
          }
      }
    }
  }

  private void runParser(){
    outputText = "<html><pre>\n";
    parseException();
    parseAlloc();
    parseText();
  }

  private class CommitAction extends AbstractAction {
    public void actionPerformed(ActionEvent ev) {
      runParser();
    }
  }

  public void changedUpdate(DocumentEvent ev) {
  }

  public void removeUpdate(DocumentEvent ev) {
  }

  public void insertUpdate(DocumentEvent ev) {
    runParser();
  }
}
