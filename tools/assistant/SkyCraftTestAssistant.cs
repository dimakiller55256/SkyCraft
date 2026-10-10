// .NET Framework Windows Forms; no Python, account or installation required.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using System.Windows.Forms;
using Microsoft.Win32;

namespace SkyCraftTests {
    static class Data {
        public static Dictionary<string,object> Obj(params object[] pairs) {
            var d = new Dictionary<string,object>();
            for (int i=0;i<pairs.Length;i+=2) d.Add((string)pairs[i],pairs[i+1]);
            return d;
        }
        public static string Json(object value) { return new JavaScriptSerializer().Serialize(value); }
        public static Dictionary<string,object> Parse(string text) { return new JavaScriptSerializer { MaxJsonLength=1024*1024 }.Deserialize<Dictionary<string,object>>(text.TrimStart('\ufeff')); }
        public static string Str(Dictionary<string,object> d,string key) { return d.ContainsKey(key) && d[key]!=null ? Convert.ToString(d[key]) : ""; }
        public static bool Bool(Dictionary<string,object> d,string key) { return d.ContainsKey(key) && d[key] is bool && (bool)d[key]; }
        public static int Int(Dictionary<string,object> d,string key) { return d.ContainsKey(key) ? Convert.ToInt32(d[key]) : 0; }
        public static Dictionary<string,object> Sub(Dictionary<string,object> d,string key) { return d.ContainsKey(key) && d[key] is Dictionary<string,object> ? (Dictionary<string,object>)d[key] : new Dictionary<string,object>(); }
        public static void Atomic(string path,object value) {
            Directory.CreateDirectory(Path.GetDirectoryName(path));
            string temp=path+"."+Guid.NewGuid().ToString("N")+".tmp";
            File.WriteAllText(temp,Json(value),new UTF8Encoding(false));
            try { if (File.Exists(path)) File.Replace(temp,path,null); else File.Move(temp,path); }
            finally { if (File.Exists(temp)) File.Delete(temp); }
        }
        public static string Quote(string s) { return "\""+Regex.Replace(s,@"(\\*)\""", "$1$1\\\"")+Regex.Match(s,@"\\*$").Value+"\""; }
        public static Dictionary<string,object> Read(string path) { return Parse(File.ReadAllText(path,Encoding.UTF8)); }
    }
    static class Discovery {
        public static readonly string SettingsDir=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"SkyCraft","TestAssistant");
        public static IEnumerable<string> Games() {
            var roots=new List<string> {
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData),"PrismLauncher"),
                Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"SkyCraft","Prism")
            };
            foreach (var drive in DriveInfo.GetDrives().Where(d=>d.IsReady && d.DriveType==DriveType.Fixed)) {
                roots.Add(Path.Combine(drive.RootDirectory.FullName,"PrismLauncher"));
                roots.Add(Path.Combine(drive.RootDirectory.FullName,"Games","PrismLauncher"));
            }
            try { foreach(var p in Process.GetProcessesByName("prismlauncher")) roots.Add(Path.GetDirectoryName(p.MainModule.FileName)); } catch { }
            var results=new HashSet<string>(StringComparer.OrdinalIgnoreCase);
            foreach(var root in roots.Distinct()) {
                string instances=Path.Combine(root,"instances");
                if(!Directory.Exists(instances)) continue;
                string[] folders;
                try { folders=Directory.GetDirectories(instances); } catch(UnauthorizedAccessException) {continue;} catch(IOException) {continue;}
                foreach(var instance in folders.Take(200)) {
                    foreach(string folder in new[]{"minecraft",".minecraft"}) {
                        string game=Path.Combine(instance,folder);
                        if(ValidGame(game)) results.Add(game);
                    }
                }
            }
            string preferences=Path.Combine(SettingsDir,"preferences.json");
            try { string game=Data.Str(Data.Read(preferences),"game"); if(ValidGame(game)) results.Add(game); } catch { }
            return results.OrderByDescending(p=>File.Exists(Path.Combine(p,"config","skycraft.properties"))).ThenBy(p=>p);
        }
        public static bool ValidGame(string path) {
            try { return Directory.Exists(Path.Combine(path,"mods")) && Directory.GetFiles(Path.Combine(path,"mods"),"*.jar").Any(f=>ReadMod(f)=="skycraft"); } catch { return false; }
        }
        public static string ReadMod(string jar) {
            try { using(var z=ZipFile.OpenRead(jar)) { var entry=z.GetEntry("fabric.mod.json"); if(entry==null || entry.Length>256000) return ""; using(var r=new StreamReader(entry.Open())) return Data.Str(Data.Parse(r.ReadToEnd()),"id"); } } catch { return ""; }
        }
        public static string SkyVersion(string game) {
            var candidates=Directory.GetFiles(Path.Combine(game,"mods"),"*.jar").Where(f=>ReadMod(f)=="skycraft").ToArray();
            if(candidates.Length!=1) return "duplicate-or-missing";
            using(var z=ZipFile.OpenRead(candidates[0])) using(var r=new StreamReader(z.GetEntry("fabric.mod.json").Open())) return Data.Str(Data.Parse(r.ReadToEnd()),"version");
        }
        public static string Skyrim() {
            var choices=new List<string>();
            try { using(var key=Registry.LocalMachine.OpenSubKey(@"SOFTWARE\WOW6432Node\Bethesda Softworks\Skyrim Special Edition")) if(key!=null) choices.Add(Convert.ToString(key.GetValue("Installed Path"))); } catch { }
            try { foreach(var p in Process.GetProcessesByName("SkyrimSE")) choices.Add(Path.GetDirectoryName(p.MainModule.FileName)); } catch { }
            try { var prefs=Data.Read(Path.Combine(SettingsDir,"preferences.json")); choices.Add(Data.Str(prefs,"skyrim")); } catch { }
            foreach(var d in DriveInfo.GetDrives().Where(d=>d.IsReady && d.DriveType==DriveType.Fixed)) {
                foreach(string p in new[]{"The Elder Scrolls V - Skyrim","Games\\Skyrim Special Edition","SteamLibrary\\steamapps\\common\\Skyrim Special Edition","Steam\\steamapps\\common\\Skyrim Special Edition"}) choices.Add(Path.Combine(d.RootDirectory.FullName,p));
            }
            return choices.FirstOrDefault(p=>!String.IsNullOrEmpty(p) && File.Exists(Path.Combine(p,"SkyrimSE.exe"))) ?? "";
        }
        public static List<Dictionary<string,object>> Addresses() {
            var rows=new List<Dictionary<string,object>>();
            foreach(var adapter in NetworkInterface.GetAllNetworkInterfaces()) {
                if(adapter.OperationalStatus!=OperationalStatus.Up || adapter.NetworkInterfaceType==NetworkInterfaceType.Loopback) continue;
                var props=adapter.GetIPProperties();
                foreach(var address in props.UnicastAddresses) {
                    var ip=address.Address;
                    if(IPAddress.IsLoopback(ip) || ip.IsIPv6LinkLocal || ip.IsIPv6Multicast || ip.ToString().StartsWith("169.254.")) continue;
                    if(ip.AddressFamily!=AddressFamily.InterNetwork && ip.AddressFamily!=AddressFamily.InterNetworkV6) continue;
                    string provider=Regex.IsMatch(adapter.Name+" "+adapter.Description,"Radmin",RegexOptions.IgnoreCase)?"radmin":Regex.IsMatch(adapter.Name+" "+adapter.Description,"Hamachi",RegexOptions.IgnoreCase)?"hamachi":Regex.IsMatch(adapter.Name+" "+adapter.Description,"Tailscale",RegexOptions.IgnoreCase)?"tailscale":"";
                    bool overlay=provider!="";
                    rows.Add(Data.Obj("ip",ip.ToString(),"scope",overlay?"overlay":Global(ip)?"global":"lan","provider",provider,"interface",adapter.Name,"type",adapter.NetworkInterfaceType.ToString(),"gateways",props.GatewayAddresses.Select(g=>g.Address.ToString()).ToArray(),"dns",props.DnsAddresses.Select(g=>g.ToString()).ToArray()));
                }
            }
            return rows;
        }
        public static bool Global(IPAddress ip) {
            var b=ip.GetAddressBytes();
            if(ip.AddressFamily==AddressFamily.InterNetworkV6) return (b[0]&0xe0)==0x20; // 2000::/3
            return !(b[0]==10 || b[0]==127 || b[0]==0 || b[0]>=224 || b[0]==169 && b[1]==254 || b[0]==172 && b[1]>=16 && b[1]<=31 || b[0]==192 && b[1]==168 || b[0]==100 && b[1]>=64 && b[1]<=127 || b[0]==198 && (b[1]==18 || b[1]==19));
        }
        public static string ProxyHint() {
            try { using(var key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Internet Settings")) {
                if(key!=null && Convert.ToInt32(key.GetValue("ProxyEnable",0))==1) {
                    string raw=Convert.ToString(key.GetValue("ProxyServer",""));
                    var match=Regex.Match(raw,@"(?:^|;)(?:https?=)?([A-Za-z0-9.\-\[\]:]+)(?:;|$)");
                    if(match.Success) return match.Groups[1].Value;
                }
            } } catch { }
            return "";
        }
        public static async Task<Dictionary<string,object>> PublicIp() {
            var attempts=new List<Dictionary<string,object>>();
            foreach(string endpoint in new[]{"https://api.ipify.org","https://api64.ipify.org"}) {
                var result=await PublicIpAt(endpoint);
                attempts.Add(result);
                if(Data.Str(result,"ip")!="") return Data.Obj("ip",Data.Str(result,"ip"),"scope","global","source",Data.Str(result,"source"),"attempts",attempts);
            }
            return Data.Obj("error","Unavailable","source","HTTPS ipify","attempts",attempts);
        }
        static async Task<Dictionary<string,object>> PublicIpAt(string endpoint) {
            try {
                var request=(HttpWebRequest)WebRequest.Create(endpoint);
                request.Proxy=null; request.Timeout=5000; request.ReadWriteTimeout=5000;
                var responseTask=request.GetResponseAsync();
                if(await Task.WhenAny(responseTask,Task.Delay(5000))!=responseTask) {request.Abort();throw new TimeoutException();}
                using(var response=await responseTask) using(var reader=new StreamReader(response.GetResponseStream())) {
                    char[] buffer=new char[65]; var read=reader.ReadBlockAsync(buffer,0,buffer.Length);
                    if(await Task.WhenAny(read,Task.Delay(5000))!=read) {request.Abort();throw new TimeoutException();}
                    string text=new string(buffer,0,await read); IPAddress ip;
                    if(text.Length>64 || !IPAddress.TryParse(text.Trim(),out ip) || !Global(ip)) throw new IOException("Invalid IP reply");
                    return Data.Obj("ip",ip.ToString(),"scope","global","source",endpoint+"; observed egress, not proven router WAN/reachability");
                }
            } catch(Exception e) { return Data.Obj("error",e.GetType().Name,"source",endpoint); }
        }
    }
    static class Assessment {
        public static bool SessionMatches(Dictionary<string,object> peer,string runId) {return Data.Str(peer,"runId")==runId && Data.Str(peer,"version")=="0.1.2-ys.network.6";}
        public static double Distance(Dictionary<string,object> a,Dictionary<string,object> b) {
            double x=Convert.ToDouble(a["x"])-Convert.ToDouble(b["x"]),y=Convert.ToDouble(a["y"])-Convert.ToDouble(b["y"]),z=Convert.ToDouble(a["z"])-Convert.ToDouble(b["z"]);
            return Math.Sqrt(x*x+y*y+z*z);
        }
    }
    sealed class Invitation {
        public string RunId; public List<Dictionary<string,object>> Addresses; public DateTime Expires; public string Authentication="ONLINE";
        public string Encode() { return "SCY2:"+Convert.ToBase64String(Encoding.UTF8.GetBytes(Data.Json(Data.Obj("schema",2,"version","0.1.2-ys.network.6","runId",RunId,"expiresUtc",Expires.ToString("o"),"addresses",Addresses,"authentication",Authentication)))); }
        public static Invitation Decode(string text) {
            if(text.Length>12000 || !text.StartsWith("SCY2:")) throw new ArgumentException("Вставьте весь код хоста, начиная с SCY2:.");
            var d=Data.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(text.Substring(5).Trim())));
            string id=Data.Str(d,"runId");
            DateTime expiry=DateTime.Parse(Data.Str(d,"expiresUtc")).ToUniversalTime();
            if(Data.Int(d,"schema")!=2 || Data.Str(d,"version")!="0.1.2-ys.network.6" || !Regex.IsMatch(id,"^[A-Za-z0-9_-]{1,64}$") || expiry<DateTime.UtcNow || expiry>DateTime.UtcNow.AddMinutes(31)) throw new ArgumentException("Код повреждён или устарел. Попросите новый код хоста.");
            string authentication=Data.Str(d,"authentication");
            if(authentication!="ONLINE" && authentication!="OFFLINE") throw new ArgumentException("В коде нет корректного режима входа. Попросите новый код хоста.");
            var rows=new List<Dictionary<string,object>>();
            foreach(object item in (System.Collections.IEnumerable)d["addresses"]) {
                var address=(Dictionary<string,object>)item; IPAddress ip;
                int port=Data.Int(address,"port"); string scope=Data.Str(address,"scope");
                if(!IPAddress.TryParse(Data.Str(address,"ip"),out ip) || port<1 || port>65535 || rows.Count>=16 || scope!="lan" && scope!="global" && scope!="overlay" || IPAddress.IsLoopback(ip) || ip.IsIPv6LinkLocal || ip.IsIPv6Multicast || ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any) || ip.AddressFamily==AddressFamily.InterNetwork && ip.GetAddressBytes()[0]>=224) throw new ArgumentException("Недопустимый адрес в коде хоста.");
                if(scope!="overlay" && (scope=="global")!=Discovery.Global(ip)) throw new ArgumentException("Неверный тип адреса в коде.");
                rows.Add(Data.Obj("ip",ip.ToString(),"port",port,"scope",scope,"provider",Data.Str(address,"provider")));
            }
            return new Invitation {RunId=id,Expires=expiry,Addresses=rows,Authentication=authentication};
        }
        public static string Endpoint(Dictionary<string,object> row) { string ip=Data.Str(row,"ip"); return (ip.Contains(":") ? "["+ip+"]" : ip)+":"+Data.Int(row,"port"); }
    }
    sealed class Bridge : IDisposable {
        readonly string dir; readonly string session=Guid.NewGuid().ToString("N");
        readonly System.Threading.Timer heartbeat; long id; bool disposed;
        public Bridge(string game) {
            dir=Path.Combine(game,"config","skycraft-test"); Directory.CreateDirectory(dir);
            string lease=Path.Combine(dir,"lease.json");
            try { var previous=Data.Read(lease); if(DateTime.Parse(Data.Str(previous,"expiresUtc")).ToUniversalTime()>DateTime.UtcNow) throw new InvalidOperationException("Другой помощник уже работает с этим экземпляром. Закройте его или подождите 15 секунд."); } catch(InvalidOperationException) { throw; } catch { }
            Renew(null); heartbeat=new System.Threading.Timer(Renew,null,2000,2000);
        }
        void Renew(object unused) { if(disposed) return; try { Data.Atomic(Path.Combine(dir,"lease.json"),Data.Obj("schema",1,"session",session,"expiresUtc",DateTime.UtcNow.AddSeconds(15).ToString("o"))); } catch { } }
        public Dictionary<string,object> State() {
            try { var state=Data.Read(Path.Combine(dir,"state.json")); if(Data.Str(state,"session")!=session || DateTime.Parse(Data.Str(state,"utc")).ToUniversalTime()<DateTime.UtcNow.AddSeconds(-5)) return new Dictionary<string,object>(); return state; } catch { return new Dictionary<string,object>(); }
        }
        public async Task<Dictionary<string,object>> Send(string action,Dictionary<string,object> args,CancellationToken token) {
            long command=++id;
            var request=args ?? new Dictionary<string,object>(); request["id"]=command; request["session"]=session; request["action"]=action;
            Data.Atomic(Path.Combine(dir,"request.json"),request);
            var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<35) {
                token.ThrowIfCancellationRequested(); var state=State();
                if(Data.Int(state,"id")==command && Data.Str(state,"result")!="running") {
                    if(Data.Str(state,"result")=="failed" && action!="probe" && action!="transport") throw new IOException("Мод не выполнил «"+action+"»: "+Data.Str(state,"error"));
                    return state;
                }
                await Task.Delay(250,token);
            }
            throw new TimeoutException("Мод не ответил. Нужен SkyCraft network.6; игра должна работать, а сохранение — быть загружено.");
        }
        public void Dispose() { disposed=true; heartbeat.Dispose(); try { var lease=Data.Read(Path.Combine(dir,"lease.json")); if(Data.Str(lease,"session")==session) File.Delete(Path.Combine(dir,"lease.json")); } catch { } }
    }
    sealed class HelperForm : Form {
        readonly ComboBox game=new ComboBox {DropDownStyle=ComboBoxStyle.DropDown,Width=600};
        readonly ComboBox role=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=150};
        readonly ComboBox network=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=160};
        readonly ComboBox mode=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=200};
        readonly TextBox proxy=new TextBox {Width=160};
        readonly TextBox invite=new TextBox {Multiline=true,Height=65,Dock=DockStyle.Fill,ScrollBars=ScrollBars.Vertical};
        readonly TextBox output=new TextBox {Multiline=true,ReadOnly=true,Dock=DockStyle.Fill,ScrollBars=ScrollBars.Vertical};
        readonly TextBox skyrim=new TextBox {Width=370};
        readonly TextBox mods=new TextBox {Width=370};
        readonly TextBox conditions=new TextBox {Width=540};
        readonly ComboBox hud=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=140};
        readonly ComboBox movement=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=140};
        readonly ComboBox blocks=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=140};
        readonly CheckBox publicIp=new CheckBox {Text="Найти внешний IP через HTTPS ipify",Checked=true,AutoSize=true};
        readonly CheckBox offlineProfiles=new CheckBox {Text="Хост: автономные профили (частная LAN/Radmin)",Checked=false,AutoSize=true};
        readonly Button start=new Button {Text="Начать автотест",AutoSize=true};
        readonly Button gameplay=new Button {Text="Игровой тест до остановки",AutoSize=true};
        readonly Dictionary<string,ComboBox> gameplayChecks=new Dictionary<string,ComboBox>();
        readonly Button stop=new Button {Text="Остановить и собрать отчёт",AutoSize=true,Enabled=false};
        readonly Button copy=new Button {Text="Копировать код хоста",AutoSize=true};
        readonly Button update=new Button {Text="Обновить мод",AutoSize=true};
        readonly Button open=new Button {Text="Открыть отчёты",AutoSize=true};
        readonly Label status=new Label {Text="Выберите роль и нажмите «Начать автотест».",AutoSize=true};
        readonly string package=AppDomain.CurrentDomain.BaseDirectory;
        string reportRoot,runId,gamePath,skyrimPath,modsPath,target="",roleName,networkLabel,selectedMode,proxyText,traceName="",hostAuthentication="ONLINE"; int networkKind; int pendingJoinRequest; int worldWaitNumber;
        Bridge bridge; CancellationTokenSource cancel; readonly List<Dictionary<string,object>> checks=new List<Dictionary<string,object>>();
        bool working,closing,gameplayRun; DateTime started; string finalArchive; string[] routeTargets=new string[0];
        public HelperForm(string initialRole) {
            Text="SkyCraft — автоматические тесты"; Size=new Size(1020,860); MinimumSize=new Size(950,820); Font=new Font("Segoe UI",10);
            role.Items.AddRange(new object[]{"Хост","Клиент","Без друга"}); role.SelectedIndex=initialRole=="client"?1:initialRole=="solo"?2:0;
            offlineProfiles.Enabled=role.SelectedIndex==0;
            role.SelectedIndexChanged+=(sender,e)=>offlineProfiles.Enabled=role.SelectedIndex==0 && !working;
            new ToolTip().SetToolTip(offlineProfiles,"OFFLINE разрешает вход без сессии Microsoft. Имена игроков не проверяются Microsoft; используйте частную сеть с доверенными участниками. Режим действует до закрытия мира.");
            network.Items.AddRange(new object[]{"Разные сети","Одна локальная сеть","Общая виртуальная сеть"}); network.SelectedIndex=0;
            mode.Items.AddRange(new object[]{"Как в настройках SkyCraft","DIRECT","SOCKS5","HTTP_CONNECT"}); mode.SelectedIndex=1; proxy.Text=Discovery.ProxyHint(); proxy.Enabled=false;
            mode.SelectedIndexChanged+=(sender,e)=>proxy.Enabled=mode.SelectedIndex>=2 && !working;
            var root=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=1,RowCount=11,Padding=new Padding(12)};
            for(int row=0;row<11;row++) root.RowStyles.Add(new RowStyle(row==8?SizeType.Percent:row==5?SizeType.Absolute:SizeType.AutoSize,row==8?100:row==5?75:0));
            root.Controls.Add(new Label {Text="Запустите Skyrim через MO2/SKSE и загрузите отдельное тестовое сохранение. Чат и IP вручную вводить не нужно.",AutoSize=true},0,0);
            var paths=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; paths.Controls.Add(new Label {Text="Minecraft:",AutoSize=true}); paths.Controls.Add(game);
            var browse=new Button {Text="Выбрать папку",AutoSize=true}; paths.Controls.Add(browse); browse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) game.Text=dialog.SelectedPath;}; root.Controls.Add(paths,0,1);
            var skyRow=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; skyRow.Controls.Add(new Label {Text="Skyrim:",AutoSize=true}); skyRow.Controls.Add(skyrim);
            var skyBrowse=new Button {Text="Выбрать",AutoSize=true}; skyRow.Controls.Add(skyBrowse); skyBrowse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) skyrim.Text=dialog.SelectedPath;}; skyRow.Controls.Add(new Label {Text="Моды MO2:",AutoSize=true}); skyRow.Controls.Add(mods);
            var modsBrowse=new Button {Text="Выбрать моды",AutoSize=true}; skyRow.Controls.Add(modsBrowse); modsBrowse.Click+=(sender,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) mods.Text=dialog.SelectedPath;}; root.Controls.Add(skyRow,0,2);
            var options=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill};
            foreach(var pair in new[]{new {Name="Роль:",Control=(Control)role},new {Name="Сеть:",Control=(Control)network},new {Name="Транспорт:",Control=(Control)mode},new {Name="Прокси:",Control=(Control)proxy}}) {options.Controls.Add(new Label {Text=pair.Name,AutoSize=true}); options.Controls.Add(pair.Control);} root.Controls.Add(options,0,3);
            var conditionRow=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; conditionRow.Controls.Add(publicIp); conditionRow.Controls.Add(offlineProfiles); conditionRow.Controls.Add(new Label {Text="Условия / имя теста:",AutoSize=true}); conditionRow.Controls.Add(conditions); root.Controls.Add(conditionRow,0,4); root.Controls.Add(invite,0,5);
            var actions=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; foreach(var control in new Control[]{start,gameplay,stop,copy,update,open}) actions.Controls.Add(control); root.Controls.Add(actions,0,6);
            var visual=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill};
            foreach(var pair in new[]{new {Name="HUD",Control=hud},new {Name="Движение",Control=movement},new {Name="Блоки у обоих",Control=blocks}}) {
                var group=new FlowLayoutPanel {AutoSize=true,WrapContents=false,Margin=new Padding(0)};
                group.Controls.Add(new Label {Text=pair.Name,AutoSize=true}); pair.Control.Items.AddRange(new object[]{"Не проверено","Работает","Ошибка"});pair.Control.SelectedIndex=0;group.Controls.Add(pair.Control);visual.Controls.Add(group);
            }
            foreach(var pair in new[]{new {Id="DIG_SHARED",Name="Разрушение у обоих"},new {Id="DOOR",Name="Проход двери"},new {Id="INPUT",Name="Клавиши"},new {Id="HOST_FOCUS",Name="Alt+Tab хоста"},new {Id="COMBAT",Name="Щит/урон"},new {Id="KNOCKDOWN",Name="Сбит с ног"}}) {
                var selector=new ComboBox {DropDownStyle=ComboBoxStyle.DropDownList,Width=115};selector.Items.AddRange(new object[]{"Не проверено","Работает","Ошибка"});selector.SelectedIndex=0;
                gameplayChecks.Add(pair.Id,selector);var group=new FlowLayoutPanel {AutoSize=true,WrapContents=false,Margin=new Padding(0)};
                group.Controls.Add(new Label {Text=pair.Name,AutoSize=true});group.Controls.Add(selector);visual.Controls.Add(group);
            }
            var gameplayHelp=new Button {Text="Игровая инструкция",AutoSize=true};gameplayHelp.Click+=(s,e)=>{string guide=Path.Combine(package,"ИГРОВЫЕ-ПРОВЕРКИ.html");if(File.Exists(guide))Process.Start(guide);};visual.Controls.Add(gameplayHelp);
            var help=new Button {Text="Инструкция",AutoSize=true};help.Click+=(sender,e)=>{string file=Path.Combine(package,"АВТОТЕСТЫ.html");if(File.Exists(file))Process.Start(file);};visual.Controls.Add(help);root.Controls.Add(visual,0,7);
            root.Controls.Add(output,0,8); root.Controls.Add(status,0,9);
            root.Controls.Add(new Label {Text="Отчёт остаётся на ПК. Код содержит адреса хоста. Внешний IP при VPN может принадлежать VPN; доступность проверяет клиент.",AutoSize=true},0,10);
            Controls.Add(root);
            foreach(string p in Discovery.Games()) game.Items.Add(p); if(game.Items.Count>0) game.SelectedIndex=0; skyrim.Text=Discovery.Skyrim(); mods.Text=Directory.Exists(Path.Combine(skyrim.Text,"Mods"))?Path.Combine(skyrim.Text,"Mods"):"";
            try {var prefs=Data.Read(Path.Combine(Discovery.SettingsDir,"preferences.json")); if(Directory.Exists(Data.Str(prefs,"mods")))mods.Text=Data.Str(prefs,"mods");}catch{}
            start.Click+=async(s,e)=>await Run(); gameplay.Click+=async(s,e)=>await Run(true); stop.Click+=(s,e)=>{if(cancel!=null) cancel.Cancel();}; copy.Click+=(s,e)=>{if(invite.Text.StartsWith("SCY2:")) { Clipboard.SetText(invite.Text); Log("Код скопирован. Передайте его другу любым привычным способом."); }};
            open.Click+=(s,e)=>{string path=reportRoot==null?Path.Combine(Discovery.SettingsDir,"reports"):Path.GetDirectoryName(reportRoot); Directory.CreateDirectory(path); Process.Start(path);};
            update.Click+=async(s,e)=>await UpdateMod();
            FormClosing+=(s,e)=>{if(working) {e.Cancel=true; closing=true; cancel.Cancel(); Log("Завершаю тест и собираю отчёт перед закрытием…");}};
            Log("Хост: код появится автоматически. Клиент: вставьте код в большое поле. Без друга: оставьте поле пустым.");
        }
        void Log(string text) { output.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+text+Environment.NewLine); status.Text=text; }
        void Check(string id,string result,string note) { checks.Add(Data.Obj("id",id,"result",result,"note",note,"utc",DateTime.UtcNow.ToString("o"))); Log("["+result+"] "+id+": "+note); }
        async Task<Dictionary<string,object>> WaitWorld(string world,int seconds,CancellationToken token) {
            var watch=Stopwatch.StartNew(); var samples=new List<Dictionary<string,object>>(); int waitNumber=++worldWaitNumber;
            try {
            while(watch.Elapsed.TotalSeconds<seconds) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                samples.Add(state);
                if(Data.Str(state,"world")==world && (world!="local" || Data.Bool(state,"positionReady") && Data.Bool(state,"skyrimLinked") && Data.Bool(state,"skyrimReady"))) return state;
                if(world=="remote" && Data.Str(state,"world")=="local" && Data.Int(Data.Sub(state,"lastFailure"),"request")==pendingJoinRequest && pendingJoinRequest>0)
                    throw new IOException("TCP был доступен; Minecraft отказал на этапе "+Data.Str(Data.Sub(state,"lastFailure"),"phase")+": "+Data.Str(Data.Sub(state,"lastFailure"),"reason"));
                status.Text="Ожидаю мир «"+world+"» и готовность. Alt+Tab в Skyrim, не двигайтесь; помощник продолжит автоматически.";
                await Task.Delay(500,token);
            }
            throw new TimeoutException("Не дождался мира «"+world+"». Проверьте запуск, сохранение и соединение.");
            } finally {if(reportRoot!=null)Data.Atomic(Path.Combine(reportRoot,"WORLD_WAIT_"+waitNumber+".json"),Data.Obj("world",world,"elapsedSeconds",watch.Elapsed.TotalSeconds,"samples",samples));}
        }
        async Task Ready(CancellationToken token) {
            Log("Ожидаю SkyCraft network.6 и загруженное тестовое сохранение (до 10 минут)…");
            var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<600) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                if(Data.Str(state,"world")!="none" && state.Count>0 && Data.Bool(state,"skyrimReady") && Data.Bool(state,"positionReady")) return;
                await Task.Delay(500,token);
            }
            throw new TimeoutException("Нет связи с Skyrim. Проверьте версию network.6, загрузку тестового сохранения и папку Minecraft.");
        }
        async Task Run(bool gameplayMode=false) {
            if(working) return;
            gameplayRun=gameplayMode;
            try {
                gamePath=Path.GetFullPath(game.Text.Trim().Trim('"'));
                if(!Discovery.ValidGame(gamePath)) throw new ArgumentException("Не найдена папка Minecraft со SkyCraft JAR. В Prism: экземпляр → Папка Minecraft.");
                if(Discovery.SkyVersion(gamePath)!="0.1.2-ys.network.6") throw new IOException("Для помощника нужен SkyCraft network.6. Закройте Skyrim и Minecraft, нажмите «Обновить мод», затем запустите игру снова.");
                skyrimPath=skyrim.Text.Trim().Trim('"'); modsPath=Directory.Exists(mods.Text.Trim())?Path.GetFullPath(mods.Text.Trim()):"NOT_FOUND";
                roleName=role.SelectedIndex==0?"host":"client"; networkKind=network.SelectedIndex; networkLabel=role.SelectedIndex==2?"Loopback":networkKind>0?"LAN":"Internet";
                selectedMode=mode.SelectedIndex==0?"CURRENT":Convert.ToString(mode.SelectedItem); proxyText=proxy.Text.Trim();
                Invitation received=role.SelectedIndex==1?Invitation.Decode(invite.Text.Trim()):null;
                hostAuthentication=received!=null?received.Authentication:role.SelectedIndex==0 && offlineProfiles.Checked?"OFFLINE":"ONLINE";
                if(hostAuthentication=="OFFLINE" && networkKind==0) throw new ArgumentException("Для автономных профилей выберите «Общая виртуальная сеть» (Radmin) или «Одна локальная сеть». Для разных сетей используйте Microsoft-вход.");
                routeTargets=received==null?new string[0]:received.Addresses.Select(a=>Data.Str(a,"ip")).ToArray();
                runId=received==null?"A"+DateTime.UtcNow.ToString("yyyyMMdd_HHmmss")+"_"+Guid.NewGuid().ToString("N").Substring(0,6):received.RunId;
                started=DateTime.UtcNow; checks.Clear(); pendingJoinRequest=0; worldWaitNumber=0; target=""; hud.SelectedIndex=movement.SelectedIndex=blocks.SelectedIndex=0; traceName=""; finalArchive=null;
                reportRoot=Path.Combine(Discovery.SettingsDir,"reports",runId+"-"+roleName+"-"+Guid.NewGuid().ToString("N").Substring(0,6)); Directory.CreateDirectory(reportRoot);
                Data.Atomic(Path.Combine(Discovery.SettingsDir,"preferences.json"),Data.Obj("game",gamePath,"skyrim",skyrimPath,"mods",modsPath));
                working=true; start.Enabled=false; gameplay.Enabled=false; stop.Enabled=true; update.Enabled=false;
                foreach(var selector in gameplayChecks.Values)selector.SelectedIndex=0;
                foreach(Control c in new Control[]{game,role,network,mode,proxy,skyrim,mods,conditions,publicIp,offlineProfiles}) c.Enabled=false;
                invite.ReadOnly=true;
                cancel=new CancellationTokenSource(); bridge=new Bridge(gamePath); var token=cancel.Token;
                var addresses=Discovery.Addresses(); var observed=publicIp.Checked?await Discovery.PublicIp():Data.Obj("source","disabled");
                Data.Atomic(Path.Combine(reportRoot,"addresses.json"),Data.Obj("utc",DateTime.UtcNow.ToString("o"),"interfaces",addresses,"external",observed,"interpretation","Addresses are candidates; public HTTPS egress is not proof of inbound reachability."));
                Check("ADDRESS_DISCOVERY",addresses.Count>0?"PASS":"PARTIAL","Найдено адресов: "+addresses.Count+"; внешний IP: "+(Data.Str(observed,"ip")==""?"не определён":Data.Str(observed,"ip"))+".");
                await Ready(token);
                var startReply=await bridge.Send("start",Data.Obj("runId",runId,"role",roleName),token); traceName=Data.Str(startReply,"traceFile");
                if(role.SelectedIndex!=1) selectedMode="DIRECT";
                if(selectedMode!="CURRENT") await bridge.Send("configure",Data.Obj("mode",selectedMode,"proxy",proxyText),token);
                Data.Atomic(Path.Combine(reportRoot,"initial-state.json"),bridge.State());
                await Snapshot("before");
                if(role.SelectedIndex==1) await ProxyPreflight(token);
                var transport=await bridge.Send("transport",null,token);
                Check("TRANSPORT_SELF_CHECK",Data.Bool(Data.Sub(transport,"detail"),"success")?"PASS":"FAIL",Data.Json(Data.Sub(transport,"detail")));
                if(!Data.Bool(Data.Sub(transport,"detail"),"success")) throw new IOException("TRANSPORT_SELF_CHECK_FAILED: локальные проверки транспорта не прошли; дальнейший сетевой тест остановлен.");
                if(role.SelectedIndex!=0) Log("Alt+Tab в Skyrim и не двигайтесь до входа/возврата. Помощник продолжает работу в фоне; состояние клавиш войдёт в отчёт.");
                if(role.SelectedIndex==2 && gameplayRun) await GameplayMonitor(token);
                else if(role.SelectedIndex==2) await Solo(token);
                else if(role.SelectedIndex==0) await Host(addresses,observed,token);
                else await Client(received,token);
            } catch(OperationCanceledException) { Check("RUN","PARTIAL","Остановлено пользователем; завершённые проверки сохранены."); }
            catch(Exception e) { Check("RUN","BLOCKED",e.Message); }
            {
                if(working && bridge!=null) {Data.Atomic(Path.Combine(reportRoot,"last-state.json"),bridge.State());
                    foreach(var pair in new[]{new {Id="VISUAL_HUD",Control=hud},new {Id="VISUAL_MOVEMENT",Control=movement},new {Id="VISUAL_BLOCKS",Control=blocks}})
                        Check(pair.Id,pair.Control.SelectedIndex==1?"PASS":pair.Control.SelectedIndex==2?"FAIL":"NOT_RUN","Наблюдение пользователя: "+Convert.ToString(pair.Control.SelectedItem));
                    foreach(var pair in gameplayChecks)Check(pair.Key,pair.Value.SelectedIndex==1?"PASS":pair.Value.SelectedIndex==2?"FAIL":"NOT_RUN","Наблюдение пользователя: "+Convert.ToString(pair.Value.SelectedItem));
                    if(!checks.Any(c=>Data.Str(c,"id")=="RUN")) {
                        bool failed=checks.Any(c=>Data.Str(c,"result")=="FAIL" && Data.Str(c,"id")!="CANDIDATE_TCP" && Data.Str(c,"id")!="CANDIDATE_JOIN");
                        bool partial=roleName=="host" && role.SelectedIndex!=2 && !checks.Any(c=>Data.Str(c,"id")=="TWO_PLAYERS" && Data.Str(c,"result")=="PASS");
                        Check("RUN",failed?"FAIL":partial?"PARTIAL":"PASS","Итог выполненного сценария; отдельные непроверенные функции остаются NOT_RUN.");
                    }
                    try {await Snapshot("after");}catch(Exception e){Check("ENVIRONMENT_AFTER","PARTIAL",e.GetType().Name);}
                }
                if(bridge!=null) { try { if(bridge.State().Count>0) {await bridge.Send("stop",null,CancellationToken.None); await Task.Delay(2200);} } catch { } bridge.Dispose(); bridge=null; }
                if(working) { try { await Report(); } catch(Exception e) { Log("Сбор отчёта неполный: "+e.Message+". Данные остались в "+reportRoot); } }
                working=false; start.Enabled=true; gameplay.Enabled=true; stop.Enabled=false; update.Enabled=true; if(cancel!=null) cancel.Dispose();
                foreach(Control c in new Control[]{game,role,network,mode,proxy,skyrim,mods,conditions,publicIp}) c.Enabled=true;
                invite.ReadOnly=false; proxy.Enabled=mode.SelectedIndex>=2;
                offlineProfiles.Enabled=role.SelectedIndex==0;
                if(closing) Close();
            }
        }
        async Task Host(List<Dictionary<string,object>> addresses,Dictionary<string,object> observed,CancellationToken token) {
            if(Data.Str(bridge.State(),"world")!="local") {await bridge.Send("leave",null,token); await WaitWorld("local",90,token);}
            await bridge.Send("host",Data.Obj("port",25565,"authentication",hostAuthentication,"privateNetwork",networkKind>0),token);
            int port=Data.Int(bridge.State(),"hostPort");
            string actualAuthentication=Data.Str(bridge.State(),"hostAuthentication");
            if(actualAuthentication!=hostAuthentication) throw new IOException("Режим входа сервера не совпал с выбранным. Перезапустите Skyrim/Minecraft и повторите.");
            Check("HOST_AUTHENTICATION","PASS","Сервер: "+actualAuthentication+(actualAuthentication=="OFFLINE"?"; автономные профили, частная LAN/Radmin. Имена не проверяются Microsoft.":"; действующие сессии Microsoft."));
            var rows=addresses.Select(a=>Data.Obj("ip",Data.Str(a,"ip"),"scope",Data.Str(a,"scope"),"provider",Data.Str(a,"provider"),"port",port)).ToList();
            if(Data.Str(observed,"ip")!="" && !rows.Any(a=>Data.Str(a,"ip")==Data.Str(observed,"ip"))) rows.Add(Data.Obj("ip",Data.Str(observed,"ip"),"scope","global","port",port));
            rows=rows.Take(16).ToList(); invite.Text=new Invitation {RunId=runId,Addresses=rows,Expires=DateTime.UtcNow.AddMinutes(30),Authentication=actualAuthentication}.Encode();
            Check("HOST_OPEN","PASS","Мир открыт на TCP "+port+". Скопируйте код и передайте клиенту.");
            await bridge.Send("configure",Data.Obj("mode","DIRECT"),token);
            var probe=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+port),token);
            Check("HOST_LOCAL_TCP",Data.Bool(Data.Sub(probe,"detail"),"success")?"PASS":"FAIL","Проверен собственный сервер; вход друга этим не подтверждён.");
            if(!Data.Bool(Data.Sub(probe,"detail"),"success")) throw new IOException("HOST_LOCAL_TCP_FAILED: собственный сервер не отвечает; отчёт собирается, ждать друга не требуется.");
            Log("Жду клиента. После входа записываю 4 минуты; клиенту нужны время на возврат и повторный вход. Дождитесь окончания или остановите после отказа клиента.");
            var waiting=Stopwatch.StartNew(); bool found=false;
            while(waiting.Elapsed.TotalMinutes<20) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                if(Data.Int(state,"players")>=2) {found=true;break;}
                await Task.Delay(1000,token);
            }
            if(!found) {Check("TWO_PLAYERS","NOT_RUN","Клиент не появился за 20 минут.");return;}
            Check("TWO_PLAYERS","PASS","Сервер сообщил не менее двух игроков.");
            if(gameplayRun){await GameplayMonitor(token);return;}
            await Observe(240,"HOST_SESSION",true,token);
        }
        async Task Join(string endpoint,CancellationToken token) {
            var reply=await bridge.Send("join",Data.Obj("target",endpoint),token);
            pendingJoinRequest=Data.Int(Data.Sub(reply,"detail"),"joinRequest");
        }
        async Task ProxyPreflight(CancellationToken token) {
            var state=bridge.State(); string actual=Data.Str(state,"transportMode");
            if(actual=="DIRECT") {Check("PROXY_PREFLIGHT","NOT_REQUIRED","DIRECT использует маршруты Windows, включая действующий VPN/WARP; локальный прокси не нужен.");return;}
            string endpoint=Data.Str(state,"proxyEndpoint"); Uri uri;
            if(!Uri.TryCreate("tcp://"+endpoint,UriKind.Absolute,out uri) || uri.Port<1) throw new IOException("Не задан корректный адрес прокси. Выберите DIRECT или адрес реально запущенного SOCKS5/HTTP-прокси.");
            using(var socket=new TcpClient()) {
                var task=socket.ConnectAsync(uri.DnsSafeHost,uri.Port);
                if(await Task.WhenAny(task,Task.Delay(3000,token))!=task) {token.ThrowIfCancellationRequested();Check("PROXY_PREFLIGHT","FAIL",endpoint+" не ответил за 3 с.");throw new IOException("PROXY_UNAVAILABLE: локальный/выбранный прокси недоступен. Адреса хоста ещё не проверялись.");}
                try {await task;} catch {Check("PROXY_PREFLIGHT","FAIL",endpoint+" отклонил TCP.");throw new IOException("PROXY_UNAVAILABLE: прокси "+endpoint+" не слушает порт. Проверьте его программу и порт либо выберите DIRECT.");}
            }
            Check("PROXY_PREFLIGHT","PASS",endpoint+" слушает TCP. Проверка выбранного протокола и целевого адреса выполняется далее модом.");
        }
        async Task VerifyPeer(CancellationToken token) {
            var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<5) {
                token.ThrowIfCancellationRequested(); var peer=Data.Sub(bridge.State(),"peerSession");
                if(peer.Count>0) {
                    bool matches=Assessment.SessionMatches(peer,runId);
                    Check("PEER_SESSION",matches?"PASS":"FAIL","Ожидался "+runId+" / network.6; сервер сообщил "+Data.Json(peer));
                    if(!matches)throw new IOException("SESSION_MISMATCH: код принадлежит другому или уже завершённому тесту. Хост должен начать новый прогон и передать новый код.");return;
                }
                await Task.Delay(250,token);
            }
            Check("PEER_SESSION","FAIL","Сервер не прислал метаданные SkyCraft network.6.");
            throw new IOException("PEER_VERSION_UNKNOWN: проверьте network.6 на хосте и запущенный помощник.");
        }
        async Task Snapshot(string stage) {
            string plan=Path.Combine(reportRoot,"environment-"+stage+"-plan.json");
            Data.Atomic(plan,Data.Obj("action","environment","output",Path.Combine(reportRoot,"environment-"+stage+".json"),"targets",routeTargets));
            await Worker(plan);
        }
        async Task Client(Invitation received,CancellationToken token) {
            Check("HOST_AUTHENTICATION","INFO","Режим из кода хоста: "+received.Authentication+(received.Authentication=="OFFLINE"?"; допускает автономный профиль в частной сети.":"; требуется действующая сессия Microsoft."));
            var candidates=received.Addresses.Where(a=>networkKind==0?Data.Str(a,"scope")=="global":networkKind==1?Data.Str(a,"scope")=="lan":Data.Str(a,"scope")=="overlay")
                .OrderBy(a=>Data.Str(a,"provider")=="radmin"?0:1).ThenBy(a=>Data.Str(a,"ip").Contains(":")?1:0).ToList();
            if(candidates.Count==0) throw new IOException("В коде нет адресов выбранной сети. Для Radmin выберите «Общая виртуальная сеть» на клиенте; для физической LAN — «Одна локальная сеть».");
            Log("Проверяю "+candidates.Count+" адресов только выбранной сети; режим "+Data.Str(bridge.State(),"transportMode")+". Код действителен до "+received.Expires.ToLocalTime().ToString("HH:mm")+".");
            int tcpReady=0;
            foreach(var candidate in candidates) {
                string endpoint=Invitation.Endpoint(candidate);
                var state=await bridge.Send("probe",Data.Obj("target",endpoint),token);
                bool ok=Data.Bool(Data.Sub(state,"detail"),"success"); Check("CANDIDATE_TCP",ok?"PASS":"FAIL",endpoint+" "+Data.Json(Data.Sub(state,"detail")));
                if(ok) {
                    tcpReady++; target=endpoint; var before=await WaitWorld("local",90,token);
                    Exception joinFailure=null;
                    try {await Join(endpoint,token); await WaitWorld("remote",90,token);await VerifyPeer(token);break;}
                    catch(OperationCanceledException) {throw;}
                    catch(Exception e) {joinFailure=e;}
                    if(joinFailure!=null) {
                        Check("CANDIDATE_JOIN","FAIL",endpoint+": "+joinFailure.Message);
                        await bridge.Send("leave",null,token); await WaitWorld("local",90,token);
                        await Recovery(before,"JOIN_RECOVERY_"+tcpReady,token); target="";
                    }
                }
            }
            if(target=="") throw new IOException(tcpReady>0?"GAME_LOGIN_FAILED: TCP доступен, но вход Minecraft не состоялся. Причины и фаза сохранены в JSONL и last-state.json; проброс порта сам по себе это не исправит.":"TARGET_TCP_UNAVAILABLE: выбранные адреса не ответили. Маршруты, запущенные фильтры и результаты каждого адреса записаны в отчёт. Внешний IP VPN не гарантирует входящий доступ.");
            Check("REMOTE_JOIN","PASS","Minecraft вошёл на "+target+".");
            if(gameplayRun){await GameplayMonitor(token);return;}
            await Observe(180,"CLIENT_SESSION",false,token);
            var departure=bridge.State(); await bridge.Send("leave",null,token); await WaitWorld("local",90,token);
            await Recovery(departure,"LEAVE_RECOVERY",token);
            await Join(target,token); await WaitWorld("remote",90,token);await VerifyPeer(token); Check("REJOIN","PASS","Повторный вход выполнен.");
            await Observe(15,"REJOIN_SESSION",false,token);
            Log("Тест завершён; клиент остаётся у хоста. Возврат в свой мир: обычная команда /leave.");
        }
        async Task Recovery(Dictionary<string,object> before,string name,CancellationToken token) {
            var samples=new List<Dictionary<string,object>>(); double maximum=0; bool valid=true,movementDetected=false; var watch=Stopwatch.StartNew();
            var baseline=Data.Sub(before,"skyPosition");
            try {
                while(watch.Elapsed.TotalSeconds<5) {
                    token.ThrowIfCancellationRequested(); var state=bridge.State(); samples.Add(state);
                    valid &= Data.Str(state,"world")=="local" && Data.Bool(state,"skyrimLinked") && Data.Bool(state,"positionReady");
                    movementDetected |= Data.Sub(state,"movementInput").Values.Any(value=>value is bool && (bool)value);
                    var position=Data.Sub(state,"skyPosition");
                    if(position.Count==0 || baseline.Count==0) valid=false;
                    else maximum=Math.Max(maximum,Assessment.Distance(baseline,position));
                    status.Text=name+": проверка позиции 5 с. Не двигайтесь.";
                    await Task.Delay(500,token);
                }
            } finally {Data.Atomic(Path.Combine(reportRoot,name+".json"),Data.Obj("before",before,"samples",samples,"maximumSkyDriftBlocks",maximum,"toleranceBlocks",3,"movementInputDetected",movementDetected));}
            if(valid && movementDetected) {Check(name,"INCONCLUSIVE","Во время измерения зарегистрированы клавиши движения; смещение "+maximum.ToString("F3")+" блока. Повторите неподвижно: эти данные не доказывают ошибку телепортации.");throw new IOException("POSITION_CHECK_INCONCLUSIVE: зарегистрировано движение. Переходы остановлены, отчёт сохраняется.");}
            bool pass=valid && maximum<=3;
            Check(name,pass?"PASS":"FAIL","Мир и связь Skyrim; максимальное смещение от исходной позиции: "+maximum.ToString("F3")+" блока (допуск 3, включая подъём из геометрии до 2,5). Проверка требует неподвижного игрока.");
            if(!pass) throw new IOException("POSITION_RECOVERY_FAILED: позиция/связь не восстановлены. Дальнейшие переходы остановлены; отчёт собирается автоматически.");
        }
        async Task Solo(CancellationToken token) {
            if(Data.Str(bridge.State(),"world")!="local" || Data.Int(bridge.State(),"players")>1) throw new IOException("Для проверки без друга нужен свой мир без других игроков.");
            Log("Не двигайтесь во время трёх проверок восстановления. Игра сама попробует закрытый порт и вернётся.");
            await bridge.Send("configure",Data.Obj("mode","DIRECT"),token);
            for(int trial=1;trial<=3;trial++) {
                await bridge.Send("host",Data.Obj("port",25565),token);int port=Data.Int(bridge.State(),"hostPort");
                var state=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+port),token);
                bool available=Data.Bool(Data.Sub(state,"detail"),"success");Check("SOLO_HOST_TCP_"+trial,available?"PASS":"FAIL","Собственный сервер 127.0.0.1:"+port);
                if(!available) throw new IOException("Собственный сервер недоступен.");
                var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int closedPort=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();string closed="127.0.0.1:"+closedPort;
                state=await bridge.Send("probe",Data.Obj("target",closed),token);
                if(Data.Bool(Data.Sub(state,"detail"),"success")) throw new IOException("Закрытый порт занят другой программой. Повторите тест.");
                Check("CLOSED_PORT_"+trial,"PASS","Ожидаемый TCP-отказ: "+closed);
                var before=await WaitWorld("local",90,token); await Join(closed,token); await Task.Delay(2000,token);await WaitWorld("local",90,token);
                var reason=Data.Sub(bridge.State(),"lastFailure");Check("DISCONNECT_REASON_"+trial,Data.Str(reason,"reason")!="" && Data.Int(reason,"request")==pendingJoinRequest?"PASS":"FAIL",Data.Json(reason));
                await Recovery(before,"FAILED_JOIN_RECOVERY_"+trial,token);
            }
            await bridge.Send("host",Data.Obj("port",25565),token);
            var restored=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+Data.Int(bridge.State(),"hostPort")),token);
            Check("DIRECT_AFTER_RECOVERY",Data.Bool(Data.Sub(restored,"detail"),"success")?"PASS":"FAIL","После трёх восстановлений DIRECT отвечает.");
            Check("TWO_PLAYER_AND_VPN_MATRIX","NOT_RUN","Один ПК проверяет локальный цикл и восстановление; межсетевой доступ и двух игроков проверяет парный тест.");
        }
        async Task Observe(int seconds,string name,bool host,CancellationToken token) {
            var samples=new List<Dictionary<string,object>>(); var watch=Stopwatch.StartNew();
            try {
                while(watch.Elapsed.TotalSeconds<seconds) {
                    token.ThrowIfCancellationRequested(); var state=bridge.State(); samples.Add(state);
                    string expected=host?"local":"remote";
                    if(Data.Str(state,"world")!=expected || !Data.Bool(state,"skyrimLinked")) {Check(name,"FAIL","Мир/связь пропали: "+Data.Json(Data.Sub(state,"lastFailure")));throw new IOException("SESSION_LOST: дальнейшие проверки остановлены.");}
                    int elapsed=(int)watch.Elapsed.TotalSeconds;
                    status.Text=name+": "+elapsed+" / "+seconds+" с; игроков "+Data.Int(state,"players")+". "+(elapsed<60?"Проверьте HUD, отметьте результат в помощнике.":elapsed<120?"Проверьте движение, затем встаньте на месте.":"Проверьте блоки у обоих, затем не двигайтесь: будет автоматический возврат.");
                    await Task.Delay(1000,token);
                }
                Check(name,"PASS",seconds+" с в мире со связью Skyrim; два игрока видны в "+samples.Count(item=>Data.Int(item,"players")>=2)+"/"+samples.Count+" отсчётах. HUD и блоки отмечаются пользователем отдельно.");
            } finally {Data.Atomic(Path.Combine(reportRoot,name+".json"),samples);}
        }
        async Task GameplayMonitor(CancellationToken token) {
            Log("Игровой тест: следуйте «Игровой инструкции». Автоматического выхода/переподключения не будет. В конце отметьте результаты и нажмите «Остановить и собрать отчёт».");
            var watch=Stopwatch.StartNew();var samples=new List<Dictionary<string,object>>();int missing=0;
            try {
                while(watch.Elapsed.TotalMinutes<30) {
                    token.ThrowIfCancellationRequested();var state=bridge.State();samples.Add(state);
                    missing=state.Count==0||!Data.Bool(state,"skyrimLinked")?missing+1:0;
                    if(missing>=8)throw new IOException("Связь с Skyrim отсутствует 8 секунд. Игровые логи и свежий дамп будут собраны автоматически.");
                    status.Text="Игровая проверка: "+(int)watch.Elapsed.TotalSeconds+" с; игроков "+Data.Int(state,"players")+". Отметьте результаты; затем «Остановить и собрать отчёт».";
                    await Task.Delay(1000,token);
                }
                Check("GAMEPLAY_WINDOW","PARTIAL","Достигнут предел 30 минут; наблюдения сохранены.");
            }finally{Data.Atomic(Path.Combine(reportRoot,"GAMEPLAY_SESSION.json"),samples);}
        }
        async Task Report() {
            Log("Собираю Windows, версии, JSONL, игровые логи и свежий локальный дамп, если он появился…");
            Data.Atomic(Path.Combine(reportRoot,"automatic-results.json"),Data.Obj("schema",2,"runId",runId,"role",role.SelectedIndex==2?"solo":roleName,"overall",checks.Where(c=>Data.Str(c,"id")=="RUN").Select(c=>Data.Str(c,"result")).LastOrDefault(),"startedUtc",started.ToString("o"),"endedUtc",DateTime.UtcNow.ToString("o"),"checks",checks,"visualGameplay","See VISUAL_* checks; user observations","conditions",conditions.Text,"networkSelection",role.SelectedIndex==2?"Loopback":networkKind==2?"Virtual":networkKind==1?"Physical LAN":"Internet","transportRequested",selectedMode,"hostAuthentication",hostAuthentication,"source","Conditions are tester annotations; environment-before/after.json contain observed facts."));
            File.WriteAllText(Path.Combine(reportRoot,"ИТОГИ.txt"),String.Join(Environment.NewLine,checks.Select(c=>"["+Data.Str(c,"result")+"] "+Data.Str(c,"id")+": "+Data.Str(c,"note")))+Environment.NewLine,Encoding.UTF8);
            string plan=Path.Combine(reportRoot,"collector-plan.json");
            Data.Atomic(plan,Data.Obj("action","collect","game",gamePath,"skyrim",skyrimPath==""?"NOT_FOUND":skyrimPath,"mods",modsPath,"output",reportRoot,"runId",runId,"role",roleName,"network",networkLabel,"target",target,"traceFile",traceName,"gameplay",true,"startedUtc",started.ToString("o")));
            await Worker(plan);
            finalArchive=reportRoot+".zip"; ZipFile.CreateFromDirectory(reportRoot,finalArchive);
            Log("Готово: "+finalArchive+". Кнопка «Открыть отчёты» покажет папку.");
        }
        async Task UpdateMod() {
            try {
                if(Process.GetProcessesByName("SkyrimSE").Length>0) throw new IOException("Закройте Skyrim обычным способом перед обновлением.");
                if(!Discovery.ValidGame(game.Text)) throw new IOException("Выберите папку Minecraft со SkyCraft.");
                string nativeDll="";
                var native=Data.Sub(Data.Read(Path.Combine(package,"package-manifest.json")),"nativeDll");
                if(Data.Bool(native,"changed")) {
                    MessageBox.Show(this,"Нужно обновить также Skyrim-часть. В MO2: правой кнопкой по включённому SkyCraft слева → Открыть в проводнике → SKSE → Plugins. В следующем окне выберите существующую SkyCraft.dll из этой папки.","Обновление SkyCraft.dll",MessageBoxButtons.OK,MessageBoxIcon.Information);
                    using(var dialog=new OpenFileDialog {Title="Выберите установленную SkyCraft.dll из MO2 → SkyCraft → SKSE → Plugins",Filter="SkyCraft.dll|SkyCraft.dll",CheckFileExists=true}) {
                        if(dialog.ShowDialog(this)!=DialogResult.OK)return;nativeDll=dialog.FileName;
                    }
                }
                update.Enabled=false; start.Enabled=false;
                Directory.CreateDirectory(Discovery.SettingsDir); string plan=Path.Combine(Discovery.SettingsDir,"update-plan.json");
                Data.Atomic(plan,Data.Obj("action","install","game",Path.GetFullPath(game.Text),"nativeDll",nativeDll));
                await Worker(plan); Log("Обновление установлено с резервной копией. Запустите SkyCraft через MO2/SKSE, затем автотест.");
            } catch(Exception e) {Log(e.Message);} finally {update.Enabled=true; start.Enabled=true;}
        }
        async Task Worker(string plan) {
            string worker=Path.Combine(package,"tools","assistant-worker.ps1");
            if(!File.Exists(worker)) throw new FileNotFoundException("Распакуйте весь комплект: нет tools/assistant-worker.ps1.");
            var info=new ProcessStartInfo(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"WindowsPowerShell","v1.0","powershell.exe"),"-NoProfile -NonInteractive -ExecutionPolicy Bypass -File "+Data.Quote(worker)+" -PlanFile "+Data.Quote(plan)) {UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8};
            using(var p=Process.Start(info)) {
                Task<string> stdout=p.StandardOutput.ReadToEndAsync(),stderr=p.StandardError.ReadToEndAsync();
                await Task.Run(()=>p.WaitForExit()); string text=await stdout; string error=await stderr;
                if(working && reportRoot!=null && Directory.Exists(reportRoot)) File.WriteAllText(Path.Combine(reportRoot,"collector-output.txt"),text+Environment.NewLine+error,Encoding.UTF8);
                if(p.ExitCode!=0) throw new IOException("Сборщик/установщик завершился с ошибкой. "+error);
            }
        }
    }
    static class Program {
        [STAThread] static int Main(string[] args) {
            ServicePointManager.SecurityProtocol=SecurityProtocolType.Tls12;
            if(args.Length>0 && args[0]=="--self-test") { return SelfTest(args.Length>1?args[1]:"self-test.json"); }
            if(args.Length>0 && args[0]=="--inventory") { Data.Atomic(args[1],Data.Obj("games",Discovery.Games().ToArray(),"skyrim",Discovery.Skyrim(),"addresses",Discovery.Addresses(),"proxyHint",Discovery.ProxyHint()));return 0; }
            if(args.Length>0 && args[0]=="--public-ip") {Data.Atomic(args[1],Discovery.PublicIp().GetAwaiter().GetResult());return 0;}
            if(args.Length>0 && args[0]=="--preview") {
                try {
                    Application.EnableVisualStyles();
                    using(var form=new HelperForm("host")) using(var bitmap=new Bitmap(form.Width,form.Height)) {
                        form.StartPosition=FormStartPosition.Manual; form.Location=new Point(-32000,-32000);
                        form.Show(); Application.DoEvents(); form.DrawToBitmap(bitmap,new Rectangle(0,0,bitmap.Width,bitmap.Height)); bitmap.Save(args[1]); form.Close();
                    }
                    return 0;
                } catch(Exception e) { Data.Atomic(args[1]+".error.json",Data.Obj("error",e.ToString())); return 1; }
            }
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            string name=Path.GetFileNameWithoutExtension(Application.ExecutablePath);
            Application.Run(new HelperForm(args.Contains("--client") || name.Contains("Клиент")?"client":args.Contains("--solo") || name.Contains("Без-друга")?"solo":"host")); return 0;
        }
        static int SelfTest(string path) {
            var checks=new List<string>();
            try {
                var rows=new List<Dictionary<string,object>> {Data.Obj("ip","192.168.1.10","scope","lan","port",25565),Data.Obj("ip","2001:4860:4860::8888","scope","global","port",25565)};
                var original=new Invitation {RunId="TEST_01",Addresses=rows,Expires=DateTime.UtcNow.AddMinutes(20)};
                var copy=Invitation.Decode(original.Encode()); if(copy.Addresses.Count!=2 || Invitation.Endpoint(copy.Addresses[1])!="[2001:4860:4860::8888]:25565") throw new Exception("Invitation roundtrip"); checks.Add("INVITATION_ROUNDTRIP_IPV4_IPV6 PASS");
                if(copy.Authentication!="ONLINE")throw new Exception("Default authentication");checks.Add("AUTHENTICATION_DEFAULT_ONLINE PASS");
                original.Authentication="OFFLINE";if(Invitation.Decode(original.Encode()).Authentication!="OFFLINE")throw new Exception("Offline authentication lost");checks.Add("OFFLINE_AUTHENTICATION_ROUNDTRIP PASS");
                original.Authentication="invalid";bool invalidAuth=false;try{Invitation.Decode(original.Encode());}catch(ArgumentException){invalidAuth=true;}if(!invalidAuth)throw new Exception("Unknown authentication accepted");checks.Add("UNKNOWN_AUTHENTICATION_REJECTED PASS");original.Authentication="ONLINE";
                original.Expires=DateTime.UtcNow.AddMinutes(-1); bool rejected=false; try {Invitation.Decode(original.Encode());} catch {rejected=true;} if(!rejected) throw new Exception("Expired invitation");checks.Add("EXPIRED_INVITATION_REJECTED PASS");
                original.Expires=DateTime.UtcNow.AddMinutes(20); original.Addresses=new List<Dictionary<string,object>>{Data.Obj("ip","127.0.0.1","scope","global","port",25565)}; rejected=false;try{Invitation.Decode(original.Encode());}catch{rejected=true;}if(!rejected)throw new Exception("Loopback invitation"); checks.Add("LOOPBACK_AND_FALSE_SCOPE_REJECTED PASS");
                string folder=Path.Combine(Path.GetDirectoryName(Path.GetFullPath(path)),"fixture-"+Guid.NewGuid().ToString("N"));Directory.CreateDirectory(folder);
                using(var bridge=new Bridge(folder)) { rejected=false;try{using(var duplicate=new Bridge(folder)) {}}catch(InvalidOperationException){rejected=true;}if(!rejected)throw new Exception("Lease lock");checks.Add("DUPLICATE_LEASE_REJECTED PASS"); }
                if(File.Exists(Path.Combine(folder,"config","skycraft-test","lease.json"))) throw new Exception("Lease cleanup");checks.Add("LEASE_REMOVED_ON_DISPOSE PASS");
                using(var bridge=new Bridge(folder)) {
                    string control=Path.Combine(folder,"config","skycraft-test");
                    var peer=Task.Run(async()=>{
                        Dictionary<string,object> request=null;
                        for(int tries=0;tries<100;tries++) {try{request=Data.Read(Path.Combine(control,"request.json"));break;}catch{} await Task.Delay(20);}
                        if(request==null) throw new Exception("No IPC request");
                        Data.Atomic(Path.Combine(control,"state.json"),Data.Obj("session","foreign","id",1,"utc",DateTime.UtcNow.ToString("o"),"result","ok"));
                        await Task.Delay(50);
                        Data.Atomic(Path.Combine(control,"state.json"),Data.Obj("session",Data.Str(request,"session"),"id",1,"utc",DateTime.UtcNow.ToString("o"),"result","running"));
                        await Task.Delay(100);
                        Data.Atomic(Path.Combine(control,"state.json"),Data.Obj("session",Data.Str(request,"session"),"id",1,"utc",DateTime.UtcNow.ToString("o"),"result","ok","detail",Data.Obj("success",true)));
                    });
                    var reply=bridge.Send("probe",Data.Obj("target","127.0.0.1:12345"),CancellationToken.None).GetAwaiter().GetResult(); peer.GetAwaiter().GetResult();
                    if(!Data.Bool(Data.Sub(reply,"detail"),"success")) throw new Exception("IPC completion"); checks.Add("IPC_FOREIGN_AND_RUNNING_REPLIES_IGNORED PASS");
                    var lease=Data.Read(Path.Combine(control,"lease.json"));
                    Data.Atomic(Path.Combine(control,"state.json"),Data.Obj("session",Data.Str(lease,"session"),"id",1,"utc",DateTime.UtcNow.AddSeconds(-10).ToString("o"),"result","ok"));
                    if(bridge.State().Count!=0) throw new Exception("Stale state accepted"); checks.Add("STALE_STATE_REJECTED PASS");
                    using(var cancellation=new CancellationTokenSource(50)) {
                        rejected=false;try{bridge.Send("probe",null,cancellation.Token).GetAwaiter().GetResult();}catch(OperationCanceledException){rejected=true;}
                        if(!rejected)throw new Exception("IPC cancellation");checks.Add("IPC_CANCELLATION PASS");
                    }
                }
                var atOrigin=Data.Obj("x",0.0,"y",0.0,"z",0.0);var away=Data.Obj("x",388.0,"y",-25.0,"z",-6.0);
                if(Assessment.Distance(away,atOrigin)<380 || Assessment.Distance(atOrigin,atOrigin)!=0)throw new Exception("Position reset classification");checks.Add("POSITION_RESET_DETECTED_LEGITIMATE_ORIGIN_ALLOWED PASS");
                var packet=Data.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(copy.Encode().Substring(5))));packet["version"]="0.1.2-ys.network.3";
                rejected=false;try{Invitation.Decode("SCY2:"+Convert.ToBase64String(Encoding.UTF8.GetBytes(Data.Json(packet))));}catch{rejected=true;}if(!rejected)throw new Exception("Old version invite");checks.Add("OLD_VERSION_INVITATION_REJECTED PASS");
                if(!Assessment.SessionMatches(Data.Obj("runId","TEST","version","0.1.2-ys.network.6"),"TEST") || Assessment.SessionMatches(Data.Obj("runId","OLD","version","0.1.2-ys.network.6"),"TEST") || Assessment.SessionMatches(Data.Obj("runId","TEST","version","0.1.2-ys.network.3"),"TEST"))throw new Exception("Peer session matching");checks.Add("PEER_SESSION_AND_VERSION_MATCHING PASS");
                Data.Atomic(path,Data.Obj("result","PASS","checks",checks));return 0;
            } catch(Exception e) {Data.Atomic(path,Data.Obj("result","FAIL","checks",checks,"error",e.ToString()));return 1;}
        }
    }
}
