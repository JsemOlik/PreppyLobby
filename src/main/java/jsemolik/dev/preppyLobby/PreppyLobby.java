package jsemolik.dev.preppyLobby;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.slf4j.Logger;

import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

@Plugin(id = "preppylobby", name = "PreppyLobby", version = "1.0-SNAPSHOT", description = "The main plugin of the Skippy collection, connecting all other plugins together.", url = "jsemolik.dev", authors = {"Oliver Steiner"})
public class PreppyLobby {

    @Inject
    private Logger logger;

    @Inject
    private ProxyServer proxyServer;

    @Inject
    @DataDirectory
    private Path dataDirectory;

    private ConfigRoot config;
    private final Set<String> dynamicCommandAliases = new HashSet<>();

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        ensureConfigPresent();
        loadConfig();

        CommandManager commandManager = proxyServer.getCommandManager();

        SimpleCommand hubCommand = new HubCommand(proxyServer, () -> {
            List<String> lobbyOrder = config != null && config.lobbies != null ? config.lobbies : Collections.emptyList();
            return new ArrayList<>(lobbyOrder);
        });
        CommandMeta meta = commandManager.metaBuilder("hub")
                .aliases("lobby")
                .build();
        commandManager.register(meta, hubCommand);

        registerDynamicCommandsFromConfig(commandManager);

        // /preppylobby reload (permission: preppylobby.reload)
        SimpleCommand adminRoot = new PreppyLobbyAdminCommand(this, proxyServer);
        CommandMeta adminMeta = commandManager.metaBuilder("preppylobby").build();
        commandManager.register(adminMeta, adminRoot);
    }

    private static final class HubCommand implements SimpleCommand {
        private final ProxyServer proxyServer;
        private final LobbySupplier lobbySupplier;

        private HubCommand(ProxyServer proxyServer, LobbySupplier lobbySupplier) {
            this.proxyServer = proxyServer;
            this.lobbySupplier = lobbySupplier;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player)) {
                return;
            }

            Player player = (Player) source;
            List<String> lobbyNames = lobbySupplier.getLobbyOrder();
            attemptSequentialConnections(player, lobbyNames, 0, true);
        }

        private void attemptSequentialConnections(Player player, List<String> lobbyNames, int index, boolean sendLobbyUnavailableOnFailure) {
            if (lobbyNames == null || lobbyNames.isEmpty()) {
                if (sendLobbyUnavailableOnFailure) {
                    player.sendMessage(Component.text("Lobby unavailable (L01)", NamedTextColor.RED));
                }
                return;
            }
            if (index >= lobbyNames.size()) {
                if (sendLobbyUnavailableOnFailure) {
                    player.sendMessage(Component.text("Lobby unavailable (L02)", NamedTextColor.RED));
                }
                return;
            }

            String serverName = lobbyNames.get(index);
            Optional<RegisteredServer> candidate = proxyServer.getServer(serverName);
            if (!candidate.isPresent()) {
                attemptSequentialConnections(player, lobbyNames, index + 1, sendLobbyUnavailableOnFailure);
                return;
            }

            RegisteredServer server = candidate.get();
            player.createConnectionRequest(server).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    attemptSequentialConnections(player, lobbyNames, index + 1, sendLobbyUnavailableOnFailure);
                }
            });
        }
    }

    private static final class SendToServerCommand implements SimpleCommand {
        private final ProxyServer proxyServer;
        private final String targetServer;

        private SendToServerCommand(ProxyServer proxyServer, String targetServer) {
            this.proxyServer = proxyServer;
            this.targetServer = targetServer;
        }

        @Override
        public void execute(Invocation invocation) {
            CommandSource source = invocation.source();
            if (!(source instanceof Player)) {
                return;
            }

            Player player = (Player) source;
            Optional<RegisteredServer> serverOpt = proxyServer.getServer(targetServer);
            if (!serverOpt.isPresent()) {
                player.sendMessage(Component.text("Server unavailable (C01)", NamedTextColor.RED));
                return;
            }
            player.createConnectionRequest(serverOpt.get()).connect().thenAccept(result -> {
                if (!result.isSuccessful()) {
                    player.sendMessage(Component.text("Server unavailable (C02)", NamedTextColor.RED));
                }
            });
        }
    }

    private interface LobbySupplier {
        List<String> getLobbyOrder();
    }

    private void registerDynamicCommandsFromConfig(CommandManager commandManager) {
        if (config != null && config.commands != null) {
            for (ServerCommandDefinition def : config.commands) {
                if (def == null || def.name == null || def.name.isEmpty() || def.target == null || def.target.isEmpty()) {
                    continue;
                }
                SimpleCommand toTarget = new SendToServerCommand(proxyServer, def.target);
                CommandMeta dynamicMeta = commandManager.metaBuilder(def.name)
                        .aliases(def.aliases == null ? new String[0] : def.aliases.toArray(new String[0]))
                        .build();
                commandManager.register(dynamicMeta, toTarget);
                dynamicCommandAliases.add(def.name);
                if (def.aliases != null) {
                    dynamicCommandAliases.addAll(def.aliases);
                }
            }
        }
    }

    private void unregisterDynamicCommands(CommandManager commandManager) {
        for (String alias : new ArrayList<>(dynamicCommandAliases)) {
            commandManager.unregister(alias);
        }
        dynamicCommandAliases.clear();
    }

    private void ensureConfigPresent() {
        try {
            Files.createDirectories(dataDirectory);
            Path configPath = dataDirectory.resolve("config.yml");
            if (Files.notExists(configPath)) {
                try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(configPath), StandardCharsets.UTF_8))) {
                    writer.write("# PreppyLobby configuration\n");
                    writer.write("lobbies:\n");
                    writer.write("  - \"Lobby1\"\n");
                    writer.write("  - \"Lobby2\"\n");
                    writer.write("\n");
                    writer.write("commands:\n");
                    writer.write("  - name: \"bedwars\"\n");
                    writer.write("    aliases: [\"bw\"]\n");
                    writer.write("    target: \"bedwars\"\n");
                    writer.write("  - name: \"duels\"\n");
                    writer.write("    target: \"duels\"\n");
                    writer.write("  - name: \"survival\"\n");
                    writer.write("    aliases: [\"smp\"]\n");
                    writer.write("    target: \"survival\"\n");
                    writer.write("  - name: \"oneblock\"\n");
                    writer.write("    aliases: [\"ob\"]\n");
                    writer.write("    target: \"oneblock\"\n");
                }
            }
        } catch (IOException e) {
            // Swallow to avoid chat spam/logs; config will be null and handled gracefully.
        }
    }

    private void loadConfig() {
        Path configPath = dataDirectory.resolve("config.yml");
        if (Files.notExists(configPath)) {
            this.config = defaultConfig();
            return;
        }
        try (InputStream in = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml(new Constructor(ConfigRoot.class, new LoaderOptions()));
            ConfigRoot loaded = yaml.load(in);
            if (loaded == null) {
                this.config = defaultConfig();
            } else {
                sanitize(loaded);
                this.config = loaded;
            }
        } catch (IOException e) {
            this.config = defaultConfig();
        }
    }

    private ConfigRoot defaultConfig() {
        ConfigRoot root = new ConfigRoot();
        root.lobbies = new ArrayList<>();
        root.lobbies.add("Lobby1");
        root.lobbies.add("Lobby2");
        root.commands = new ArrayList<>();
        return root;
    }

    private void sanitize(ConfigRoot root) {
        if (root.lobbies == null) {
            root.lobbies = new ArrayList<>();
        }
        if (root.commands == null) {
            root.commands = new ArrayList<>();
        } else {
            List<ServerCommandDefinition> cleaned = new ArrayList<>();
            for (ServerCommandDefinition def : root.commands) {
                if (def == null) continue;
                if (def.name == null || def.name.trim().isEmpty()) continue;
                if (def.target == null || def.target.trim().isEmpty()) continue;
                if (def.aliases == null) def.aliases = new ArrayList<>();
                cleaned.add(def);
            }
            root.commands = cleaned;
        }
    }

    // Called by admin command
    private void reloadConfigAndCommands(CommandSource feedbackTarget) {
        CommandManager commandManager = proxyServer.getCommandManager();
        unregisterDynamicCommands(commandManager);
        loadConfig();
        registerDynamicCommandsFromConfig(commandManager);
        if (feedbackTarget != null) {
            feedbackTarget.sendMessage(Component.text("PreppyLobby reloaded.", NamedTextColor.GREEN));
        }
    }

    public static final class ConfigRoot {
        public List<String> lobbies;
        public List<ServerCommandDefinition> commands;
    }

    public static final class ServerCommandDefinition {
        public String name;
        public List<String> aliases;
        public String target;
    }

    private static final class PreppyLobbyAdminCommand implements SimpleCommand {
        private final PreppyLobby plugin;
        private final ProxyServer proxyServer;

        private PreppyLobbyAdminCommand(PreppyLobby plugin, ProxyServer proxyServer) {
            this.plugin = plugin;
            this.proxyServer = proxyServer;
        }

        @Override
        public boolean hasPermission(Invocation invocation) {
            return invocation.source().hasPermission("preppylobby.reload");
        }

        @Override
        public void execute(Invocation invocation) {
            String[] args = invocation.arguments();
            if (args.length >= 1 && "reload".equalsIgnoreCase(args[0])) {
                plugin.reloadConfigAndCommands(invocation.source());
            }
        }

        @Override
        public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
            if (hasPermission(invocation)) {
                return CompletableFuture.completedFuture(Collections.singletonList("reload"));
            }
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
    }
}
