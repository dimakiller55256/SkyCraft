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
                    bool overlay=Regex.IsMatch(adapter.Name+" "+adapter.Description,"Radmin|Hamachi|Tailscale|WireGuard|Cloudflare|WARP",RegexOptions.IgnoreCase);
                    rows.Add(Data.Obj("ip",ip.ToString(),"scope",overlay?"overlay":Global(ip)?"global":"lan","interface",adapter.Name,"type",adapter.NetworkInterfaceType.ToString(),"gateways",props.GatewayAddresses.Select(g=>g.Address.ToString()).ToArray(),"dns",props.DnsAddresses.Select(g=>g.ToString()).ToArray()));
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
            return "127.0.0.1:8080";
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
    sealed class Invitation {
        public string RunId; public List<Dictionary<string,object>> Addresses; public DateTime Expires;
        public string Encode() { return "SCY1:"+Convert.ToBase64String(Encoding.UTF8.GetBytes(Data.Json(Data.Obj("schema",1,"runId",RunId,"expiresUtc",Expires.ToString("o"),"addresses",Addresses)))); }
        public static Invitation Decode(string text) {
            if(text.Length>12000 || !text.StartsWith("SCY1:")) throw new ArgumentException("Вставьте весь код хоста, начиная с SCY1:.");
            var d=Data.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(text.Substring(5).Trim())));
            string id=Data.Str(d,"runId");
            DateTime expiry=DateTime.Parse(Data.Str(d,"expiresUtc")).ToUniversalTime();
            if(Data.Int(d,"schema")!=1 || !Regex.IsMatch(id,"^[A-Za-z0-9_-]{1,64}$") || expiry<DateTime.UtcNow || expiry>DateTime.UtcNow.AddHours(25)) throw new ArgumentException("Код повреждён или устарел. Попросите новый код хоста.");
            var rows=new List<Dictionary<string,object>>();
            foreach(object item in (System.Collections.IEnumerable)d["addresses"]) {
                var address=(Dictionary<string,object>)item; IPAddress ip;
                int port=Data.Int(address,"port"); string scope=Data.Str(address,"scope");
                if(!IPAddress.TryParse(Data.Str(address,"ip"),out ip) || port<1 || port>65535 || rows.Count>=16 || scope!="lan" && scope!="global" && scope!="overlay" || IPAddress.IsLoopback(ip) || ip.IsIPv6LinkLocal || ip.IsIPv6Multicast || ip.Equals(IPAddress.Any) || ip.Equals(IPAddress.IPv6Any) || ip.AddressFamily==AddressFamily.InterNetwork && ip.GetAddressBytes()[0]>=224) throw new ArgumentException("Недопустимый адрес в коде хоста.");
                if(scope!="overlay" && (scope=="global")!=Discovery.Global(ip)) throw new ArgumentException("Неверный тип адреса в коде.");
                rows.Add(Data.Obj("ip",ip.ToString(),"port",port,"scope",scope));
            }
            return new Invitation {RunId=id,Expires=expiry,Addresses=rows};
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
            throw new TimeoutException("Мод не ответил. Нужен SkyCraft network.3; игра должна работать, а сохранение — быть загружено.");
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
        readonly TextBox skyrim=new TextBox {Width=500};
        readonly CheckBox publicIp=new CheckBox {Text="Найти внешний IP через HTTPS ipify",Checked=true,AutoSize=true};
        readonly Button start=new Button {Text="Начать автотест",AutoSize=true};
        readonly Button stop=new Button {Text="Остановить и собрать отчёт",AutoSize=true,Enabled=false};
        readonly Button copy=new Button {Text="Копировать код хоста",AutoSize=true};
        readonly Button update=new Button {Text="Обновить мод",AutoSize=true};
        readonly Button open=new Button {Text="Открыть отчёты",AutoSize=true};
        readonly Label status=new Label {Text="Выберите роль и нажмите «Начать автотест».",AutoSize=true};
        readonly string package=AppDomain.CurrentDomain.BaseDirectory;
        string reportRoot,runId,gamePath,skyrimPath,modsPath,target="",roleName,networkLabel,selectedMode,proxyText,traceName=""; int networkKind;
        Bridge bridge; CancellationTokenSource cancel; readonly List<Dictionary<string,object>> checks=new List<Dictionary<string,object>>();
        bool working,closing; DateTime started; string finalArchive;
        public HelperForm(string initialRole) {
            Text="SkyCraft — автоматические тесты"; Size=new Size(960,740); MinimumSize=new Size(800,650); Font=new Font("Segoe UI",10);
            role.Items.AddRange(new object[]{"Хост","Клиент","Без друга"}); role.SelectedIndex=initialRole=="client"?1:initialRole=="solo"?2:0;
            network.Items.AddRange(new object[]{"Разные сети","Одна локальная сеть","Общая виртуальная сеть"}); network.SelectedIndex=0;
            mode.Items.AddRange(new object[]{"Как в настройках SkyCraft","DIRECT","SOCKS5","HTTP_CONNECT"}); mode.SelectedIndex=0; proxy.Text=Discovery.ProxyHint();
            var root=new TableLayoutPanel {Dock=DockStyle.Fill,ColumnCount=1,RowCount=10,Padding=new Padding(12)};
            root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Absolute,75)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.Percent,100)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize)); root.RowStyles.Add(new RowStyle(SizeType.AutoSize));
            root.Controls.Add(new Label {Text="Запустите Skyrim через MO2/SKSE и загрузите отдельное тестовое сохранение. Чат и IP вручную вводить не нужно.",AutoSize=true},0,0);
            var paths=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; paths.Controls.Add(new Label {Text="Minecraft:",AutoSize=true}); paths.Controls.Add(game);
            var browse=new Button {Text="Выбрать папку",AutoSize=true}; paths.Controls.Add(browse); browse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) game.Text=dialog.SelectedPath;}; root.Controls.Add(paths,0,1);
            var skyRow=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; skyRow.Controls.Add(new Label {Text="Skyrim:",AutoSize=true}); skyRow.Controls.Add(skyrim);
            var skyBrowse=new Button {Text="Выбрать",AutoSize=true}; skyRow.Controls.Add(skyBrowse); skyBrowse.Click+=(s,e)=>{using(var dialog=new FolderBrowserDialog()) if(dialog.ShowDialog()==DialogResult.OK) skyrim.Text=dialog.SelectedPath;}; root.Controls.Add(skyRow,0,2);
            var options=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; options.Controls.Add(role); options.Controls.Add(network); options.Controls.Add(mode); options.Controls.Add(proxy); root.Controls.Add(options,0,3);
            root.Controls.Add(publicIp,0,4); root.Controls.Add(invite,0,5);
            var actions=new FlowLayoutPanel {AutoSize=true,Dock=DockStyle.Fill}; foreach(var control in new Control[]{start,stop,copy,update,open}) actions.Controls.Add(control); root.Controls.Add(actions,0,6);
            root.Controls.Add(output,0,7); root.Controls.Add(status,0,8);
            root.Controls.Add(new Label {Text="Отчёт остаётся на ПК. Код содержит адреса хоста. Внешний IP при VPN может принадлежать VPN; доступность проверяет клиент.",AutoSize=true},0,9);
            Controls.Add(root);
            foreach(string p in Discovery.Games()) game.Items.Add(p); if(game.Items.Count>0) game.SelectedIndex=0; skyrim.Text=Discovery.Skyrim();
            start.Click+=async(s,e)=>await Run(); stop.Click+=(s,e)=>{if(cancel!=null) cancel.Cancel();}; copy.Click+=(s,e)=>{if(invite.Text.StartsWith("SCY1:")) { Clipboard.SetText(invite.Text); Log("Код скопирован. Передайте его другу любым привычным способом."); }};
            open.Click+=(s,e)=>{string path=reportRoot==null?Path.Combine(Discovery.SettingsDir,"reports"):Path.GetDirectoryName(reportRoot); Directory.CreateDirectory(path); Process.Start(path);};
            update.Click+=async(s,e)=>await UpdateMod();
            FormClosing+=(s,e)=>{if(working) {e.Cancel=true; closing=true; cancel.Cancel(); Log("Завершаю тест и собираю отчёт перед закрытием…");}};
            Log("Хост: код появится автоматически. Клиент: вставьте код в большое поле. Без друга: оставьте поле пустым.");
        }
        void Log(string text) { output.AppendText(DateTime.Now.ToString("HH:mm:ss")+"  "+text+Environment.NewLine); status.Text=text; }
        void Check(string id,string result,string note) { checks.Add(Data.Obj("id",id,"result",result,"note",note,"utc",DateTime.UtcNow.ToString("o"))); Log("["+result+"] "+id+": "+note); }
        async Task<Dictionary<string,object>> WaitWorld(string world,int seconds,CancellationToken token) {
            var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<seconds) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                if(Data.Str(state,"world")==world) return state;
                if(world=="remote" && Data.Str(state,"world")=="local") throw new IOException("Вход отклонён; мод вернулся в собственный мир.");
                await Task.Delay(500,token);
            }
            throw new TimeoutException("Не дождался мира «"+world+"». Проверьте запуск, сохранение и соединение.");
        }
        async Task Ready(CancellationToken token) {
            Log("Ожидаю SkyCraft network.3 и загруженное тестовое сохранение (до 10 минут)…");
            var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<600) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                if(Data.Str(state,"world")!="none" && state.Count>0 && Data.Bool(state,"skyrimReady")) return;
                await Task.Delay(500,token);
            }
            throw new TimeoutException("Нет связи с Skyrim. Проверьте версию network.3, загрузку тестового сохранения и папку Minecraft.");
        }
        async Task Run() {
            if(working) return;
            try {
                gamePath=Path.GetFullPath(game.Text.Trim().Trim('"'));
                if(!Discovery.ValidGame(gamePath)) throw new ArgumentException("Не найдена папка Minecraft со SkyCraft JAR. В Prism: экземпляр → Папка Minecraft.");
                if(Discovery.SkyVersion(gamePath)!="0.1.2-ys.network.3") throw new IOException("Для помощника нужен SkyCraft network.3. Закройте Skyrim и Minecraft, нажмите «Обновить мод», затем запустите игру снова.");
                skyrimPath=skyrim.Text.Trim().Trim('"'); modsPath=Directory.Exists(Path.Combine(skyrimPath,"Mods"))?Path.Combine(skyrimPath,"Mods"):"NOT_FOUND";
                roleName=role.SelectedIndex==0?"host":"client"; networkKind=network.SelectedIndex; networkLabel=role.SelectedIndex==2?"Loopback":networkKind>0?"LAN":"Internet";
                selectedMode=mode.SelectedIndex==0?"CURRENT":Convert.ToString(mode.SelectedItem); proxyText=proxy.Text.Trim();
                Invitation received=role.SelectedIndex==1?Invitation.Decode(invite.Text.Trim()):null;
                runId=received==null?"A"+DateTime.UtcNow.ToString("yyyyMMdd_HHmmss")+"_"+Guid.NewGuid().ToString("N").Substring(0,6):received.RunId;
                started=DateTime.UtcNow; checks.Clear(); target=""; traceName=""; finalArchive=null;
                reportRoot=Path.Combine(Discovery.SettingsDir,"reports",runId+"-"+roleName+"-"+Guid.NewGuid().ToString("N").Substring(0,6)); Directory.CreateDirectory(reportRoot);
                Data.Atomic(Path.Combine(Discovery.SettingsDir,"preferences.json"),Data.Obj("game",gamePath,"skyrim",skyrimPath));
                working=true; start.Enabled=false; stop.Enabled=true; update.Enabled=false;
                foreach(Control c in new Control[]{game,role,network,mode,proxy,skyrim,publicIp}) c.Enabled=false;
                invite.ReadOnly=true;
                cancel=new CancellationTokenSource(); bridge=new Bridge(gamePath); var token=cancel.Token;
                var addresses=Discovery.Addresses(); var observed=publicIp.Checked?await Discovery.PublicIp():Data.Obj("source","disabled");
                Data.Atomic(Path.Combine(reportRoot,"addresses.json"),Data.Obj("utc",DateTime.UtcNow.ToString("o"),"interfaces",addresses,"external",observed,"interpretation","Addresses are candidates; public HTTPS egress is not proof of inbound reachability."));
                Check("ADDRESS_DISCOVERY",addresses.Count>0?"PASS":"PARTIAL","Найдено адресов: "+addresses.Count+"; внешний IP: "+(Data.Str(observed,"ip")==""?"не определён":Data.Str(observed,"ip"))+".");
                await Ready(token);
                var startReply=await bridge.Send("start",Data.Obj("runId",runId,"role",roleName),token); traceName=Data.Str(startReply,"traceFile");
                if(selectedMode!="CURRENT") await bridge.Send("configure",Data.Obj("mode",selectedMode,"proxy",proxyText),token);
                var transport=await bridge.Send("transport",null,token);
                Check("TRANSPORT_SELF_CHECK",Data.Bool(Data.Sub(transport,"detail"),"success")?"PASS":"FAIL",Data.Json(Data.Sub(transport,"detail")));
                if(role.SelectedIndex==2) await Solo(token);
                else if(role.SelectedIndex==0) await Host(addresses,observed,token);
                else await Client(received,token);
            } catch(OperationCanceledException) { Check("RUN","PARTIAL","Остановлено пользователем; завершённые проверки сохранены."); }
            catch(Exception e) { Check("RUN","BLOCKED",e.Message); }
            {
                if(bridge!=null) { try { if(bridge.State().Count>0) {await bridge.Send("stop",null,CancellationToken.None); await Task.Delay(2200);} } catch { } bridge.Dispose(); bridge=null; }
                if(working) { try { await Report(); } catch(Exception e) { Log("Сбор отчёта неполный: "+e.Message+". Данные остались в "+reportRoot); } }
                working=false; start.Enabled=true; stop.Enabled=false; update.Enabled=true; if(cancel!=null) cancel.Dispose();
                foreach(Control c in new Control[]{game,role,network,mode,proxy,skyrim,publicIp}) c.Enabled=true;
                invite.ReadOnly=false;
                if(closing) Close();
            }
        }
        async Task Host(List<Dictionary<string,object>> addresses,Dictionary<string,object> observed,CancellationToken token) {
            if(Data.Str(bridge.State(),"world")!="local") {await bridge.Send("leave",null,token); await WaitWorld("local",90,token);}
            await bridge.Send("host",Data.Obj("port",25565),token);
            int port=Data.Int(bridge.State(),"hostPort");
            var rows=addresses.Select(a=>Data.Obj("ip",Data.Str(a,"ip"),"scope",Data.Str(a,"scope"),"port",port)).ToList();
            if(Data.Str(observed,"ip")!="" && !rows.Any(a=>Data.Str(a,"ip")==Data.Str(observed,"ip"))) rows.Add(Data.Obj("ip",Data.Str(observed,"ip"),"scope","global","port",port));
            rows=rows.Take(16).ToList(); invite.Text=new Invitation {RunId=runId,Addresses=rows,Expires=DateTime.UtcNow.AddHours(24)}.Encode();
            Check("HOST_OPEN","PASS","Мир открыт на TCP "+port+". Скопируйте код и передайте клиенту.");
            await bridge.Send("configure",Data.Obj("mode","DIRECT"),token);
            var probe=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+port),token);
            Check("HOST_LOCAL_TCP",Data.Bool(Data.Sub(probe,"detail"),"success")?"PASS":"FAIL","Проверен собственный сервер; вход друга этим не подтверждён.");
            await bridge.Send("restore",null,token);
            Log("Жду клиента. Когда игрок войдёт, автоматически запишу 3 минуты наблюдений. Можно играть и проверять HUD/блоки.");
            var waiting=Stopwatch.StartNew(); bool found=false;
            while(waiting.Elapsed.TotalMinutes<20) {
                token.ThrowIfCancellationRequested(); var state=bridge.State();
                if(Data.Int(state,"players")>=2) {found=true;break;}
                await Task.Delay(1000,token);
            }
            if(!found) {Check("TWO_PLAYERS","NOT_RUN","Клиент не появился за 20 минут.");return;}
            Check("TWO_PLAYERS","PASS","Сервер сообщил не менее двух игроков.");
            await Observe(180,"HOST_SESSION",true,token);
            Check("VISUAL_GAMEPLAY","NOT_RUN","HUD, движение, блоки и квесты требуют наблюдения игроков; программа не выдаёт им PASS.");
        }
        async Task Client(Invitation received,CancellationToken token) {
            var candidates=received.Addresses.Where(a=>Data.Str(a,"scope")=="global" || networkKind==1 && Data.Str(a,"scope")=="lan" || networkKind==2 && Data.Str(a,"scope")=="overlay").OrderBy(a=>Data.Str(a,"scope")=="global"?1:0).ToList();
            if(candidates.Count==0) throw new IOException("В коде нет адреса для выбранной сети. Хосту нужен глобальный IPv6 или входящий IPv4/TCP. Проверьте выбор «Одна локальная сеть».");
            Log("Автоматически проверяю "+candidates.Count+" адресов через выбранный транспорт…");
            foreach(var candidate in candidates) {
                string endpoint=Invitation.Endpoint(candidate);
                var state=await bridge.Send("probe",Data.Obj("target",endpoint),token);
                bool ok=Data.Bool(Data.Sub(state,"detail"),"success"); Check("CANDIDATE_TCP",ok?"PASS":"FAIL",endpoint+" "+Data.Json(Data.Sub(state,"detail")));
                if(ok) {
                    Exception joinFailure=null;
                    try {
                        await bridge.Send("join",Data.Obj("target",endpoint),token); await WaitWorld("remote",90,token);
                        target=endpoint; break;
                    } catch(OperationCanceledException) {throw;} catch(Exception e) {joinFailure=e;}
                    if(joinFailure!=null) {
                        Check("CANDIDATE_JOIN","FAIL",endpoint+": "+joinFailure.Message);
                        await bridge.Send("leave",null,token); await WaitWorld("local",90,token);
                    }
                }
            }
            if(target=="") throw new IOException("Ни один адрес хоста не доступен. Проверьте TCP 25565/брандмауэр/проброс порта. При CGNAT или блокировке входящего VPN потребуется доступный IPv6 или ретранслятор; повторный сбор IP этого не исправит.");
            Check("REMOTE_JOIN","PASS","Minecraft вошёл на "+target+".");
            await Observe(180,"CLIENT_SESSION",false,token);
            await bridge.Send("leave",null,token); await WaitWorld("local",90,token); Check("LEAVE_RECOVERY","PASS","Вернулся в собственный мир.");
            await bridge.Send("join",Data.Obj("target",target),token); await WaitWorld("remote",90,token); Check("REJOIN","PASS","Повторный вход выполнен.");
            await Observe(15,"REJOIN_SESSION",false,token);
            Check("VISUAL_GAMEPLAY","NOT_RUN","HUD/движение/синхронизация блоков не измерены программой.");
            Log("Тест завершён; клиент остаётся у хоста. Возврат в свой мир: обычная команда /leave.");
        }
        async Task Solo(CancellationToken token) {
            if(Data.Str(bridge.State(),"world")!="local") throw new IOException("Для проверки без друга нужен собственный мир. Вернитесь /leave и повторите.");
            if(Data.Int(bridge.State(),"players")>1) throw new IOException("В мире есть другие игроки. Проверка без друга не должна прерывать их сессию.");
            await bridge.Send("configure",Data.Obj("mode","DIRECT"),token);
            await bridge.Send("host",Data.Obj("port",25565),token); int port=Data.Int(bridge.State(),"hostPort");
            var state=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+port),token); Check("SOLO_HOST_TCP",Data.Bool(Data.Sub(state,"detail"),"success")?"PASS":"FAIL","Проверен опубликованный локальный мир.");
            var listener=new TcpListener(IPAddress.Loopback,0); listener.Start(); int closedPort=((IPEndPoint)listener.LocalEndpoint).Port; listener.Stop();
            string closed="127.0.0.1:"+closedPort;
            state=await bridge.Send("probe",Data.Obj("target",closed),token);
            if(Data.Bool(Data.Sub(state,"detail"),"success")) throw new IOException("Выбранный закрытый порт успела занять другая программа. Повторите тест.");
            Check("CLOSED_PORT","PASS","Ожидаемый отказ: "+closed+".");
            var before=Data.Sub(bridge.State(),"position");
            await bridge.Send("join",Data.Obj("target",closed),token); await Task.Delay(2000,token); state=await WaitWorld("local",90,token);
            Check("FAILED_JOIN_RECOVERY","PASS","После неудачного входа собственный мир восстановлен.");
            Check("POSITION_OBSERVATION","INFO","Позиция до: "+Data.Json(before)+"; после: "+Data.Json(Data.Sub(state,"position"))+". Точные переходы записаны в JSONL; смещение требует анализа SKY-001.");
            await bridge.Send("host",Data.Obj("port",port),token);
            state=await bridge.Send("probe",Data.Obj("target","127.0.0.1:"+port),token);
            Check("DIRECT_AFTER_RECOVERY",Data.Bool(Data.Sub(state,"detail"),"success")?"PASS":"FAIL","После восстановления DIRECT отвечает.");
            Check("TWO_PLAYER_AND_VPN_MATRIX","NOT_RUN","Для совместной игры нужен второй ПК. VPN/zapret/Cloudflare включаются пользователем, затем этот тест запускается повторно.");
        }
        async Task Observe(int seconds,string name,bool host,CancellationToken token) {
            var samples=new List<Dictionary<string,object>>(); var watch=Stopwatch.StartNew();
            while(watch.Elapsed.TotalSeconds<seconds) {
                token.ThrowIfCancellationRequested(); var state=bridge.State(); samples.Add(state);
                string expected=host?"local":"remote";
                if(Data.Str(state,"world")!=expected) {Data.Atomic(Path.Combine(reportRoot,name+".json"),samples); Check(name,"FAIL","Мир/связь пропали во время наблюдения.");return;}
                status.Text=name+": "+(int)watch.Elapsed.TotalSeconds+" / "+seconds+" с; игроков "+Data.Int(state,"players");
                await Task.Delay(1000,token);
            }
            Data.Atomic(Path.Combine(reportRoot,name+".json"),samples);
            Check(name,"PASS",seconds+" с в мире Minecraft; доля связи Skyrim: "+samples.Count(s=>Data.Bool(s,"skyrimLinked"))+"/"+samples.Count+". Графика и блоки не проверялись.");
        }
        async Task Report() {
            Log("Собираю Windows, версии, JSONL и итоговый ZIP…");
            Data.Atomic(Path.Combine(reportRoot,"automatic-results.json"),Data.Obj("schema",1,"runId",runId,"role",roleName,"startedUtc",started.ToString("o"),"endedUtc",DateTime.UtcNow.ToString("o"),"checks",checks,"visualGameplay","NOT_RUN","conditions","Process/adapter hints only; VPN/filter state is not inferred as proven."));
            File.WriteAllText(Path.Combine(reportRoot,"ИТОГИ.txt"),String.Join(Environment.NewLine,checks.Select(c=>"["+Data.Str(c,"result")+"] "+Data.Str(c,"id")+": "+Data.Str(c,"note")))+Environment.NewLine,Encoding.UTF8);
            string plan=Path.Combine(reportRoot,"collector-plan.json");
            Data.Atomic(plan,Data.Obj("action","collect","game",gamePath,"skyrim",skyrimPath==""?"NOT_FOUND":skyrimPath,"mods",modsPath,"output",reportRoot,"runId",runId,"role",roleName,"network",networkLabel,"target",target,"traceFile",traceName));
            await Worker(plan);
            finalArchive=reportRoot+".zip"; ZipFile.CreateFromDirectory(reportRoot,finalArchive);
            Log("Готово: "+finalArchive+". Кнопка «Открыть отчёты» покажет папку.");
        }
        async Task UpdateMod() {
            try {
                if(Process.GetProcessesByName("SkyrimSE").Length>0) throw new IOException("Закройте Skyrim обычным способом перед обновлением.");
                if(!Discovery.ValidGame(game.Text)) throw new IOException("Выберите папку Minecraft со SkyCraft.");
                update.Enabled=false; start.Enabled=false;
                Directory.CreateDirectory(Discovery.SettingsDir); string plan=Path.Combine(Discovery.SettingsDir,"update-plan.json");
                Data.Atomic(plan,Data.Obj("action","install","game",Path.GetFullPath(game.Text)));
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
                var original=new Invitation {RunId="TEST_01",Addresses=rows,Expires=DateTime.UtcNow.AddHours(1)};
                var copy=Invitation.Decode(original.Encode()); if(copy.Addresses.Count!=2 || Invitation.Endpoint(copy.Addresses[1])!="[2001:4860:4860::8888]:25565") throw new Exception("Invitation roundtrip"); checks.Add("INVITATION_ROUNDTRIP_IPV4_IPV6 PASS");
                original.Expires=DateTime.UtcNow.AddMinutes(-1); bool rejected=false; try {Invitation.Decode(original.Encode());} catch {rejected=true;} if(!rejected) throw new Exception("Expired invitation");checks.Add("EXPIRED_INVITATION_REJECTED PASS");
                original.Expires=DateTime.UtcNow.AddHours(1); original.Addresses=new List<Dictionary<string,object>>{Data.Obj("ip","127.0.0.1","scope","global","port",25565)}; rejected=false;try{Invitation.Decode(original.Encode());}catch{rejected=true;}if(!rejected)throw new Exception("Loopback invitation"); checks.Add("LOOPBACK_AND_FALSE_SCOPE_REJECTED PASS");
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
                Data.Atomic(path,Data.Obj("result","PASS","checks",checks));return 0;
            } catch(Exception e) {Data.Atomic(path,Data.Obj("result","FAIL","checks",checks,"error",e.ToString()));return 1;}
        }
    }
}
