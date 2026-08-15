; Builds the Windows installer from the jpackage app image:
; iscc /DAppVersion=0.1.0 /DImageDir=<app image> /DOutputDir=<dir> axolotl.iss

[Setup]
AppId={{6F1C9B2E-4D7A-4C3B-9E21-8A5F0D3C7B14}
AppName=Axolotl
AppVersion={#AppVersion}
AppPublisher=Axolotl
AppPublisherURL=https://github.com/succubus-uwu/axolotl
DefaultDirName={autopf}\Axolotl
DisableProgramGroupPage=yes
OutputDir={#OutputDir}
OutputBaseFilename=axolotl-{#AppVersion}-windows-x64-setup
Compression=lzma2
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
ChangesEnvironment=yes
PrivilegesRequiredOverridesAllowed=dialog
UninstallDisplayIcon={app}\axl.exe
WizardStyle=modern

[InstallDelete]
; The jars and the repository carry the version in their names, so an upgrade would keep the old ones.
Type: filesandordirs; Name: "{app}\app"
Type: filesandordirs; Name: "{app}\runtime"
Type: filesandordirs; Name: "{app}\repository"

[Files]
Source: "{#ImageDir}\*"; DestDir: "{app}"; Flags: recursesubdirs createallsubdirs ignoreversion

[Run]
Filename: "{app}\axl.exe"; Parameters: "setup"; Flags: runhidden runasoriginaluser; StatusMsg: "Installing the Axolotl libraries for Maven and Gradle..."

[UninstallRun]
; The compiler daemon keeps the jars open, and Windows does not delete open files.
Filename: "{app}\axl.exe"; Parameters: "daemon --stop"; Flags: runhidden; RunOnceId: "StopDaemon"

[Code]
// axl.exe goes on the PATH: the machine's for an installation for all users, the user's otherwise.
function PathRoot: Integer;
begin
  if IsAdminInstallMode then Result := HKEY_LOCAL_MACHINE else Result := HKEY_CURRENT_USER;
end;

function PathKey: String;
begin
  if IsAdminInstallMode then
    Result := 'SYSTEM\CurrentControlSet\Control\Session Manager\Environment'
  else
    Result := 'Environment';
end;

function CurrentPath: String;
begin
  if not RegQueryStringValue(PathRoot, PathKey, 'Path', Result) then Result := '';
end;

procedure AddToPath(Dir: String);
var
  Path: String;
begin
  Path := CurrentPath;
  if Pos(';' + Uppercase(Dir) + ';', ';' + Uppercase(Path) + ';') > 0 then exit;
  if (Path <> '') and (Copy(Path, Length(Path), 1) <> ';') then Path := Path + ';';
  RegWriteExpandStringValue(PathRoot, PathKey, 'Path', Path + Dir);
end;

procedure RemoveFromPath(Dir: String);
var
  Path: String;
  P: Integer;
begin
  Path := ';' + CurrentPath + ';';
  P := Pos(';' + Uppercase(Dir) + ';', Uppercase(Path));
  if P = 0 then exit;
  Delete(Path, P, Length(Dir) + 1);
  RegWriteExpandStringValue(PathRoot, PathKey, 'Path', Copy(Path, 2, Length(Path) - 2));
end;

// An upgrade replaces the jars, which the compiler daemon of the installed version keeps open.
// Versions without a daemon do not know the command and change nothing.
function PrepareToInstall(var NeedsRestart: Boolean): String;
var
  ResultCode: Integer;
begin
  if FileExists(ExpandConstant('{app}\axl.exe')) then
    ExecAsOriginalUser(ExpandConstant('{app}\axl.exe'), 'daemon --stop', '', SW_HIDE, ewWaitUntilTerminated, ResultCode);
  Result := '';
end;

procedure CurStepChanged(CurStep: TSetupStep);
begin
  if CurStep = ssPostInstall then AddToPath(ExpandConstant('{app}'));
end;

procedure CurUninstallStepChanged(CurUninstallStep: TUninstallStep);
begin
  if CurUninstallStep = usPostUninstall then RemoveFromPath(ExpandConstant('{app}'));
end;
