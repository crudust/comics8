Set WshShell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
currentDir = fso.GetParentFolderName(WScript.ScriptFullName)
WshShell.Run "javaw -Xmx2048m -jar """ & currentDir & "\Comics8.jar""", 0, False
