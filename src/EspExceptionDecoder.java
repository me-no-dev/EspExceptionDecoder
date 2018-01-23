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
import processing.app.helpers.OSUtils;
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

  // Original code from processing.app.helpers.ProcessUtils.exec()
  // Need custom version to redirect STDERR to STDOUT for GDB processing
  public static Process execRedirected(String[] command) throws IOException {
    ProcessBuilder pb;

    // No problems on linux and mac
    if (!OSUtils.isWindows()) {
      pb = new ProcessBuilder(command);
    } else {
      // Brutal hack to workaround windows command line parsing.
      // http://stackoverflow.com/questions/5969724/java-runtime-exec-fails-to-escape-characters-properly
      // http://msdn.microsoft.com/en-us/library/a1y7w461.aspx
      // http://bugs.sun.com/view_bug.do?bug_id=6468220
      // http://bugs.sun.com/view_bug.do?bug_id=6518827
      String[] cmdLine = new String[command.length];
      for (int i = 0; i < command.length; i++)
        cmdLine[i] = command[i].replace("\"", "\\\"");
      pb = new ProcessBuilder(cmdLine);
      Map<String, String> env = pb.environment();
      env.put("CYGWIN", "nodosfilewarning");
    }
    pb.redirectErrorStream(true);

    return pb.start();
  }

  private int listenOnProcess(String[] arguments){
    try {
      final Process p = execRedirected(arguments);
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

    String gdb;
    if(PreferencesData.get("runtime.os").contentEquals("windows"))
      gdb = "xtensa-"+tc+"-elf-gdb.exe";
    else
      gdb = "xtensa-"+tc+"-elf-gdb";

    tool = new File(gccPath + "/bin", gdb);
    if (!tool.exists() || !tool.isFile()) {
      System.err.println();
      editor.statusError("ERROR: "+gdb+" not found!");
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

  private String prettyPrintGDBLine(String line) {
    String address = "", method = "", file = "", fileline = "", html = "";

    if (!line.startsWith("0x")) {
      return null;
    }
  
    address = line.substring(0, line.indexOf(' '));
    line = line.substring(line.indexOf(' ') + 1);

    int atIndex = line.indexOf("is in ");
    if(atIndex == -1) {
      return null;
    }
    try {
        method = line.substring(atIndex + 6, line.lastIndexOf('(') - 1);
        fileline = line.substring(line.lastIndexOf('(') + 1, line.lastIndexOf(')'));
        file = fileline.substring(0, fileline.lastIndexOf(':'));
        line = fileline.substring(fileline.lastIndexOf(':') + 1);
        if(file.length() > 0){
          int lastfs = file.lastIndexOf('/');
          int lastbs = file.lastIndexOf('\\');
          int slash = (lastfs > lastbs)?lastfs:lastbs;
          if(slash != -1){
            String filename = file.substring(slash+1);
            file = file.substring(0,slash+1) + "<b>" + filename + "</b>";
          }
        }
        html = "<font color=green>" + address + ": </font>" +
               "<b><font color=blue>" + method + "</font></b> at " +
               file + " line <b>" + line + "</b>";
    } catch (Exception e) {
        // Something weird in the GDB output format, report what we can
        html = "<font color=green>" + address + ": </font> " + line;
    }

    return html;
  }

  private void printLine(String line){
    String s = prettyPrintGDBLine(line);
    if (s != null) 
      outputText += s +"\n";
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

  // Strip out just the STACK lines or BACKTRACE line, and generate the reference log
  private void parseStackOrBacktrace(String regexp, boolean multiLine, String stripAfter) {
    String content = inputArea.getText();

    Pattern strip;
    if (multiLine) strip = Pattern.compile(regexp, Pattern.DOTALL);
    else strip = Pattern.compile(regexp);
    Matcher stripMatch = strip.matcher(content);
    if (!stripMatch.find()) {
      return; // Didn't find it in the text box.
    }

    // Strip out just the interesting bits to make RexExp sane
    content = content.substring(stripMatch.start(), stripMatch.end());

    if (stripAfter != null) {
      Pattern after = Pattern.compile(stripAfter);
      Matcher afterMatch = after.matcher(content);
      if (afterMatch.find()) {
          content = content.substring(0, afterMatch.start());
      }
    }

    // Anything looking like an instruction address, dump!
    Pattern p = Pattern.compile("40[0-2](\\d|[a-f]|[A-F]){5}\\b");
    int count = 0;
    Matcher m = p.matcher(content);
    while(m.find()) {
      count ++;
    }
    if(count == 0){
      return;
    }
    String command[] = new String[7 + count*2];
    int i = 0;
    command[i++] = tool.getAbsolutePath();
    command[i++] = "--batch";
    command[i++] = elf.getAbsolutePath();
    command[i++] = "-ex";
    command[i++] = "set listsize 1";
    m = p.matcher(content);
    while(m.find()) {
      command[i++] = "-ex";
      command[i++] = "l *0x"+content.substring(m.start(), m.end());
    }
    command[i++] = "-ex";
    command[i++] = "q";
    outputText += "\n<i>Decoding stack results</i>\n";
    sysExec(command);
  }

  // Heavyweight call GDB, run list on address, and return result if it succeeded
  private String decodeFunctionAtAddress( String addr ) {
    String command[] = new String[9];
    command[0] = tool.getAbsolutePath();
    command[1] = "--batch";
    command[2] = elf.getAbsolutePath();
    command[3] = "-ex";
    command[4] = "set listsize 1";
    command[5] = "-ex";
    command[6] = "l *0x" + addr;
    command[7] = "-ex";
    command[8] = "q";

    try {
      final Process proc = execRedirected(command);
      InputStreamReader reader = new InputStreamReader(proc.getInputStream());
      int c;
      String line = "";
      while ((c = reader.read()) != -1){
        if((char)c == '\r')
          continue;
        if((char)c == '\n' && line != ""){
          reader.close();
          return prettyPrintGDBLine(line);
        } else {
         line += (char)c;
        }
      }
      reader.close();
    } catch (Exception er) { }
    // Something went wrong
    return null;
  }

  // Scan and report the last failed memory allocation attempt, if present on the ESP8266
  private void parseAlloc() {
    String content = inputArea.getText();
    Pattern p = Pattern.compile("last failed alloc call: 40[0-2](\\d|[a-f]|[A-F]){5}\\((\\d)+\\)");
    Matcher m = p.matcher(content);
    if (m.find()) {
      String fs = content.substring(m.start(), m.end());
      Pattern p2 = Pattern.compile("40[0-2](\\d|[a-f]|[A-F]){5}\\b");
      Matcher m2 = p2.matcher(fs);
      if (m2.find()) {
        String addr = fs.substring(m2.start(), m2.end());
        Pattern p3 = Pattern.compile("\\((\\d)+\\)");
        Matcher m3 = p3.matcher(fs);
        if (m3.find()) {
          String size = fs.substring(m3.start()+1, m3.end()-1);
          String line = decodeFunctionAtAddress(addr);
          if (line != null) {
            outputText += "Memory allocation of " + size + " bytes failed at " + line + "\n";
          }
        }
      }
    }
  }

  // Filter out a register output given a regex (ESP8266/ESP32 differ in format)
  private void parseRegister(String regName, String prettyName) {
    String content = inputArea.getText();
    Pattern p = Pattern.compile(regName + "(\\d|[a-f]|[A-F]){8}\\b");
    Matcher m = p.matcher(content);
    if (m.find()) {
      String fs = content.substring(m.start(), m.end());
      Pattern p2 = Pattern.compile("(\\d|[a-f]|[A-F]){8}\\b");
      Matcher m2 = p2.matcher(fs);
      if (m2.find()) {
        String addr = fs.substring(m2.start(), m2.end());
        String line = decodeFunctionAtAddress(addr);
        if (line != null) {
          outputText += prettyName + ": " + line + "\n";
        } else {
          outputText += prettyName + ": <font color=\"green\">0x" + addr + "</font>\n";
        }
      }
    }
  }

  private void runParser(){
    outputText = "<html><pre>\n";
    // Main error cause
    parseException();
    // ESP8266 register format
    parseRegister("epc1=0x", "<font color=\"red\">PC</font>");
    parseRegister("excvaddr=0x", "<font color=\"red\">EXCVADDR</font>");
    // ESP32 register format
    parseRegister("PC\\s*:\\s*(0x)?", "<font color=\"red\">PC</font>");
    parseRegister("EXCVADDR\\s*:\\s*(0x)?", "<font color=\"red\">EXCVADDR</font>");
    // Last memory allocation failure
    parseAlloc();
    // The stack on ESP8266, multiline
    parseStackOrBacktrace(">>>stack>>>(.)*", true, "<<<stack<<<");
    // The backtrace on ESP32, one-line only
    parseStackOrBacktrace("Backtrace:(.)*", false, null);
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
