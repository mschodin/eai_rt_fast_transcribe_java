using TranscriberApp.Components;

// Ensure ffmpeg and dotnet are on PATH for the process
var home = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile);
var extraPaths = $"{home}/.local/bin:{home}/.dotnet";
var currentPath = Environment.GetEnvironmentVariable("PATH") ?? "";
if (!currentPath.Contains(".local/bin"))
    Environment.SetEnvironmentVariable("PATH", $"{extraPaths}:{currentPath}");

var builder = WebApplication.CreateBuilder(args);

// Add services to the container.
builder.Services.AddRazorComponents()
    .AddInteractiveServerComponents();

var app = builder.Build();

// Configure the HTTP request pipeline.
if (!app.Environment.IsDevelopment())
{
    app.UseExceptionHandler("/Error", createScopeForErrors: true);
}

app.UseStaticFiles();
app.UseAntiforgery();

app.MapRazorComponents<App>()
    .AddInteractiveServerRenderMode();

app.Run();
