package de.presti.ree6.webinterface.controller;

import com.github.philippheuer.credentialmanager.domain.OAuth2Credential;
import com.github.twitch4j.auth.domain.TwitchScopes;
import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import com.jagrosh.jdautilities.oauth2.Scope;
import com.jagrosh.jdautilities.oauth2.entities.OAuth2Guild;
import com.jagrosh.jdautilities.oauth2.entities.OAuth2User;
import com.jagrosh.jdautilities.oauth2.session.Session;
import de.presti.ree6.sql.SQLSession;
import de.presti.ree6.webinterface.Server;
import de.presti.ree6.webinterface.bot.BotWorker;
import de.presti.ree6.webinterface.bot.version.BotVersion;
import de.presti.ree6.webinterface.controller.forms.ChannelChangeForm;
import de.presti.ree6.webinterface.controller.forms.SettingChangeForm;
import de.presti.ree6.sql.entities.Recording;
import de.presti.ree6.sql.entities.Setting;
import de.presti.ree6.sql.entities.level.ChatUserLevel;
import de.presti.ree6.sql.entities.level.VoiceUserLevel;
import de.presti.ree6.sql.entities.stats.GuildCommandStats;
import de.presti.ree6.sql.entities.webhook.Webhook;
import de.presti.ree6.webinterface.invite.InviteContainerManager;
import de.presti.ree6.webinterface.utils.data.CustomOAuth2Credential;
import de.presti.ree6.webinterface.utils.data.UserLevelContainer;
import de.presti.ree6.webinterface.utils.others.RandomUtils;
import de.presti.ree6.webinterface.utils.others.SessionUtil;
import io.sentry.Sentry;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Controller for the Frontend to manage what the user sees.
 */
@SuppressWarnings("DuplicatedCode")
@Controller(value = "/")
public class FrontendController {

    /**
     * Paths to Thymeleaf Templates.
     */
    private static final String MAIN_PATH = "main/index", ERROR_400_PATH = "error/400/index", ERROR_403_PATH = "error/403/index", ERROR_404_PATH = "error/404/index", ERROR_500_PATH = "error/500/index", MODERATION_PATH = "panel/moderation/index", SOCIAL_PATH = "panel/social/index", LOGGING_PATH = "panel/logging/index", RECORDING_PATH = "panel/recording/index";

    /**
     * A Get Mapper for the Main Page.
     *
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping("/")
    public String main(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        try {
            if (!SessionUtil.checkIdentifier(id)) {
                // Try retrieving the Session from the Identifier.
                Session session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);
                if (session != null) {
                    model.addAttribute("isLogged", true);
                }
            }
        } catch (Exception ignore) {
        }
        return MAIN_PATH;
    }

    //region Twitch

    /**
     * A Get Mapper for generation of a Twitch OAuth2 Credentials
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Identifier.
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping("/twitch/auth")
    public ModelAndView startTwitchAuth(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id) {
        ModelAndView modelAndView = new ModelAndView();

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            modelAndView.getModelMap().addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
            deleteSessionCookie(httpServletResponse);
            modelAndView.setViewName(ERROR_403_PATH);
        } else {
            modelAndView.setViewName("redirect:" +
                    Server.getInstance().getTwitchIdentityProvider()
                            .getAuthenticationUrl(List.of(TwitchScopes.CHAT_CHANNEL_MODERATE, TwitchScopes.CHAT_READ,
                                            TwitchScopes.HELIX_CHANNEL_SUBSCRIPTIONS_READ, TwitchScopes.HELIX_CHANNEL_HYPE_TRAIN_READ,
                                            TwitchScopes.HELIX_CHANNEL_REDEMPTIONS_READ),
                                    RandomUtils.randomString(6)));
        }

        return modelAndView;
    }

    /**
     * The Request Mapper for the Twitch Auth callback.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Identifier.
     * @param code                the OAuth2 Code from Twitch.
     * @param state               the local State of the OAuth2 Credentials.
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping(value = "/twitch/auth/callback")
    public ModelAndView twitchLogin(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam String code, @RequestParam String state) {
        ModelAndView modelAndView = new ModelAndView();

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            modelAndView.getModelMap().addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
            deleteSessionCookie(httpServletResponse);
            modelAndView.setViewName(ERROR_403_PATH);
        } else {
            OAuth2Credential oAuth2Credential = null;

            Session session = null;

            OAuth2User oAuth2User = null;

            try {
                // Try retrieving the Session from the Identifier.
                session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

                // Try building the credentials.
                oAuth2Credential = Server.getInstance().getTwitchIdentityProvider().getCredentialByCode(code);

                if (session != null) {
                    // Try retrieving the User from the Session.
                    oAuth2User = Server.getInstance().getOAuth2Client().getUser(session).complete();
                }
            } catch (Exception ignore) {
            }

            // If the given data was valid and the credentials are build. Redirect to success page.
            if (oAuth2Credential != null && oAuth2User != null) {
                CustomOAuth2Credential oAuth2Credential1 = new CustomOAuth2Credential(oAuth2Credential);
                oAuth2Credential1.setDiscordId(oAuth2User.getIdLong());
                Server.getInstance().getCredentialManager().addCredential("twitch", oAuth2Credential1);
                Server.getInstance().getCredentialManager().save();
                modelAndView.setViewName("redirect:" + (BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD ? "https://cp.ree6.de" : "http://10.8.0.1:8887") + "/auth/twitch/index");
            } else {
                modelAndView.getModelMap().addAttribute("errorMessage", "Invalid Credentials - Please check if everything is correct!");
                modelAndView.setViewName(ERROR_403_PATH);
            }
        }

        return modelAndView;
    }

    //endregion

    //region Discord.

    /**
     * A Get Mapper for generation of a Discord OAuth2 Session
     *
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping("/discord/auth")
    public ModelAndView startDiscordAuth() {
        return new ModelAndView("redirect:" + Server.getInstance().getOAuth2Client().generateAuthorizationURL((BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD ? "https://cp.ree6.de" : "http://10.8.0.1:8887") + "/discord/auth/callback", Scope.GUILDS, Scope.IDENTIFY, Scope.GUILDS_JOIN));
    }

    /**
     * The Request Mapper for the Discord Auth callback.
     *
     * @param httpServletResponse the HTTP Response.
     * @param code                the OAuth2 Code from Discord.
     * @param state               the local State of the OAuth2 Session.
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping(value = "/discord/auth/callback")
    public ModelAndView discordLogin(HttpServletResponse httpServletResponse, @RequestParam String code, @RequestParam String state) {
        Session session = null;

        // Generate a secure Base64 String for the Identifier.
        String identifier = RandomUtils.getRandomBase64String(128);

        try {
            // Try creating a Session.
            session = Server.getInstance().getOAuth2Client().startSession(code, state, identifier, Scope.GUILDS, Scope.IDENTIFY, Scope.GUILDS_JOIN).complete();
        } catch (Exception ignore) {
        }

        // If the given data was valid and a Session has been created redirect to the panel Site. If not redirect to error.
        if (session != null) {

            Cookie cookie = new Cookie("identifier", Base64.getEncoder().encodeToString(identifier.getBytes(StandardCharsets.UTF_8)));

            cookie.setHttpOnly(true);
            cookie.setMaxAge(7 * 24 * 60 * 60);
            if (BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD) cookie.setSecure(true);
            cookie.setPath("/");

            httpServletResponse.addCookie(cookie);

            try {
                Server.getInstance().getOAuth2Client().getUser(session).queue(oAuth2User -> {
                    if (oAuth2User != null) {
                        Guild guild = BotWorker.getShardManager().getGuildById(805149057004732457L);
                        if (guild != null) {
                            try {
                                Server.getInstance().getOAuth2Client().joinGuild(oAuth2User, guild).queue();
                            } catch (Exception exception) {
                                Sentry.captureException(exception);
                            }
                        }
                    }
                });
            } catch (Exception exception) {
                Sentry.captureException(exception);
            }

            return new ModelAndView("redirect:" + (BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD ? "https://cp.ree6.de" : "http://10.8.0.1:8887") + "/panel");
        } else {
            return new ModelAndView("redirect:" + (BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD ? "https://cp.ree6.de" : "http://10.8.0.1:8887") + "/error");
        }
    }

    //endregion

    //region Leaderboard.

    /**
     * A Get Mapper for the Leaderboard Page.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Identifier of the Session.
     * @param guildId             the Guild ID of the Guild.
     * @param model               the Model for Thymeleaf.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(value = "/leaderboard")
    public String getLeaderboard(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {
        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        try {
            // Try retrieving the Session from the Identifier.
            Session session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

            if (session == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("isLogged", true);

            // Try retrieving the User from the Session.
            OAuth2User oAuth2User = Server.getInstance().getOAuth2Client().getUser(session).complete();

            // Retrieve the Guild by its giving ID.
            Guild guild = BotWorker.getShardManager().getGuildById(guildId);

            // If the Guild couldn't be loaded redirect to Error page.
            if (guild == null) {
                model.addAttribute("errorMessage", "The requested Guild is Invalid or not recognized!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_404_PATH;
            }

            Member member = guild.retrieveMemberById(oAuth2User.getId()).complete();

            if (member == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - You are not part of this Guild!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("guild", guild);
        } catch (Exception exception) {
            Sentry.captureException(exception);
            model.addAttribute("title", "Unexpected Error, please Report!");
            model.addAttribute("errorMessage", "We received an unexpected error, please report this to the developer! (" + exception.getMessage() + ")");
            return ERROR_500_PATH;
        }

        return "leaderboard/index";
    }

    /**
     * The Request Mapper for the Guild Leaderboard.
     *
     * @param guildId the ID of the Guild.
     * @param model   the ViewModel.
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping(value = "/leaderboard/chat")
    public String getLeaderboardChat(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        try {
            // Try retrieving the Session from the Identifier.
            Session session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

            if (session == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("isLogged", true);

            // Try retrieving the User from the Session.
            OAuth2User oAuth2User = Server.getInstance().getOAuth2Client().getUser(session).complete();

            // Retrieve the Guild by its giving ID.
            Guild guild = BotWorker.getShardManager().getGuildById(guildId);

            // If the Guild couldn't be loaded redirect to Error page.
            if (guild == null) {
                model.addAttribute("errorMessage", "The requested Guild is Invalid or not recognized!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_404_PATH;
            }

            Member member = guild.retrieveMemberById(oAuth2User.getId()).complete();

            if (member == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - You are not part of this Guild!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("guild", guild);
        } catch (Exception exception) {
            Sentry.captureException(exception);
            model.addAttribute("errorMessage", "We received an unexpected error, please report this to the developer! (" + exception.getMessage() + ")");
            return ERROR_500_PATH;
        }

        List<ChatUserLevel> userLevels = SQLSession.getSqlConnector().getSqlWorker().getTopChat(guildId, 5);
        List<UserLevelContainer> containerUserLevels = new ArrayList<>();
        for (ChatUserLevel userLevel : userLevels) {
            try {
                UserLevelContainer userLevelContainer = new UserLevelContainer();
                userLevelContainer.setUserLevel(userLevel);

                if (BotWorker.getShardManager().getUserById(userLevel.getUserId()) != null) {
                    userLevelContainer.setUser(BotWorker.getShardManager().getUserById(userLevel.getUserId()));
                } else {
                    userLevelContainer.setUser(BotWorker.getShardManager().retrieveUserById(userLevel.getUserId()).complete());
                }
                containerUserLevels.add(userLevelContainer);
            } catch (Exception ignore) {
                userLevel.setExperience(0);
                SQLSession.getSqlConnector().getSqlWorker().addChatLevelData(guildId, userLevel);
            }
        }

        model.addAttribute("userLevels", containerUserLevels);

        return "leaderboard/index2";
    }

    /**
     * The Request Mapper for the Guild Leaderboard.
     *
     * @param guildId the ID of the Guild.
     * @param model   the ViewModel.
     * @return {@link ModelAndView} with the redirect data.
     */
    @GetMapping(value = "/leaderboard/voice")
    public String getLeaderboardVoice(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        try {
            // Try retrieving the Session from the Identifier.
            Session session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

            if (session == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - Please check if you are logged in!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("isLogged", true);

            // Try retrieving the User from the Session.
            OAuth2User oAuth2User = Server.getInstance().getOAuth2Client().getUser(session).complete();

            // Retrieve the Guild by its giving ID.
            Guild guild = BotWorker.getShardManager().getGuildById(guildId);

            // If the Guild couldn't be loaded redirect to Error page.
            if (guild == null) {
                model.addAttribute("errorMessage", "The requested Guild is Invalid or not recognized!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_404_PATH;
            }

            Member member = guild.retrieveMemberById(oAuth2User.getId()).complete();

            if (member == null) {
                model.addAttribute("errorMessage", "Insufficient Permissions - You are not part of this Guild!");
                deleteSessionCookie(httpServletResponse);
                return ERROR_403_PATH;
            }

            model.addAttribute("guild", guild);
        } catch (Exception exception) {
            Sentry.captureException(exception);
            model.addAttribute("errorMessage", "We received an unexpected error, please report this to the developer! (" + exception.getMessage() + ")");
            return ERROR_500_PATH;
        }

        List<VoiceUserLevel> userLevels = SQLSession.getSqlConnector().getSqlWorker().getTopVoice(guildId, 5);
        List<UserLevelContainer> containerUserLevels = new ArrayList<>();
        for (VoiceUserLevel userLevel : userLevels) {
            try {
                UserLevelContainer userLevelContainer = new UserLevelContainer();
                userLevelContainer.setUserLevel(userLevel);

                if (BotWorker.getShardManager().getUserById(userLevel.getUserId()) != null) {
                    userLevelContainer.setUser(BotWorker.getShardManager().getUserById(userLevel.getUserId()));
                } else {
                    userLevelContainer.setUser(BotWorker.getShardManager().retrieveUserById(userLevel.getUserId()).complete());
                }
                containerUserLevels.add(userLevelContainer);
            } catch (Exception ignore) {
                userLevel.setExperience(0);
                SQLSession.getSqlConnector().getSqlWorker().addVoiceLevelData(guildId, userLevel);
            }
        }

        model.addAttribute("userLevels", containerUserLevels);

        return "leaderboard/index2";
    }

    //endregion

    //region Panel

    /**
     * Request Mapper for the Server selection Panel.
     *
     * @param id    the Session Identifier.
     * @param model the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/panel")
    public String openPanel(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        Session session = null;
        List<OAuth2Guild> guilds;

        try {
            // Try retrieving the Session from the Identifier.
            session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

            // Try retrieving the Guilds of the OAuth2 User.
            guilds = Server.getInstance().getOAuth2Client().getGuilds(session).complete();

            // Remove every Guild from the List where the OAuth2 User doesn't have Administration permission.
            guilds.removeIf(oAuth2Guild -> !oAuth2Guild.hasPermission(Permission.ADMINISTRATOR));

            // Set the Identifier.
            model.addAttribute("identifier", id);

            // Add the Guilds as Attribute to the ViewModel.
            model.addAttribute("guilds", guilds);

            model.addAttribute("isLogged", true);
        } catch (Exception e) {
            // If the Session is null just return to the default Page.
            if (session == null) {
                deleteSessionCookie(httpServletResponse);
                return MAIN_PATH;
            }

            // If the Session isn't null give the User a Notification that his Guilds couldn't be loaded.
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            return ERROR_403_PATH;
        }

        // Return Panel Page.
        return "panel/index";
    }

    //endregion

    //region Server

    /**
     * Request Mapper for the Server Panel Page.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Session Identifier.
     * @param guildId             the ID of the selected Guild.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/server")
    public String openServerPanel(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, guildId, id)) return ERROR_403_PATH;

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("invites", InviteContainerManager.getInvites(guildId));

        StringBuilder commandStats = new StringBuilder();

        for (GuildCommandStats entry : SQLSession.getSqlConnector().getSqlWorker().getStats(guildId)) {
            commandStats.append(entry.getCommand()).append(" - ").append(entry.getUses()).append(", ");
        }

        if (commandStats.length() > 0) {
            commandStats = new StringBuilder(commandStats.substring(0, commandStats.length() - 2));
        } else {
            commandStats = new StringBuilder("None.");
        }

        model.addAttribute("commandstats", commandStats.toString());

        // Return to the Server Panel Page.
        return "panel/server/index";
    }

    //endregion

    //region Moderation

    /**
     * Request Mapper for the Moderation Panel Page.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Session Identifier.
     * @param guildId             the ID of the selected Guild.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/moderation")
    public String openPanelModeration(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, guildId, id)) return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("roles", guild.getRoles());
        model.addAttribute("channels", guild.getTextChannels());
        model.addAttribute("commands", SQLSession.getSqlConnector().getSqlWorker().getAllSettings(guildId).stream().filter(setting -> setting.getName().startsWith("com")).toList());
        model.addAttribute("prefixSetting", SQLSession.getSqlConnector().getSqlWorker().getSetting(guildId, "chatprefix"));
        model.addAttribute("words", SQLSession.getSqlConnector().getSqlWorker().getChatProtectorWords(guildId));

        List<Role> roles = new ArrayList<>();

        for (de.presti.ree6.sql.entities.roles.Role role : SQLSession.getSqlConnector().getSqlWorker().getAutoRoles(guild.getId())) {
            try {
                roles.add(guild.getRoleById(role.getRoleId()));
            } catch (Exception ignore) {
                SQLSession.getSqlConnector().getSqlWorker().removeAutoRole(guild.getId(), role.getRoleId());
            }
        }

        model.addAttribute("autoroles", roles);

        // Return to the Moderation Panel Page.
        return MODERATION_PATH;
    }

    /**
     * Request Mapper for the Moderation Settings Change Panel.
     *
     * @param httpServletResponse the HTTP Response.
     * @param settingChangeForm   as the Form which contains the needed data.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @PostMapping(path = "/moderation/settings")
    public String openPanelModeration(HttpServletResponse httpServletResponse, @ModelAttribute(name = "settingChangeForm") SettingChangeForm settingChangeForm, Model model) {

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, settingChangeForm.getGuild(), settingChangeForm.getIdentifier()))
            return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        if (settingChangeForm.getSetting().getName() == null || settingChangeForm.getSetting().getValue() == null)
            return ERROR_400_PATH;

        // Change the Setting Data.
        if (!settingChangeForm.getSetting().getName().equalsIgnoreCase("addBadWord") && !settingChangeForm.getSetting().getName().equalsIgnoreCase("removeBadWord") && !settingChangeForm.getSetting().getName().equalsIgnoreCase("addAutoRole") && !settingChangeForm.getSetting().getName().equalsIgnoreCase("removeAutoRole")) {
            SQLSession.getSqlConnector().getSqlWorker().setSetting(settingChangeForm.getSetting());
        } else {
            switch (settingChangeForm.getSetting().getName()) {
                case "addBadWord" ->
                        SQLSession.getSqlConnector().getSqlWorker().addChatProtectorWord(settingChangeForm.getGuild(), settingChangeForm.getSetting().getStringValue());
                case "removeBadWord" ->
                        SQLSession.getSqlConnector().getSqlWorker().removeChatProtectorWord(settingChangeForm.getGuild(), settingChangeForm.getSetting().getStringValue());
                case "addAutoRole" ->
                        SQLSession.getSqlConnector().getSqlWorker().addAutoRole(settingChangeForm.getGuild(), settingChangeForm.getSetting().getStringValue());
                case "removeAutoRole" ->
                        SQLSession.getSqlConnector().getSqlWorker().removeAutoRole(settingChangeForm.getGuild(), settingChangeForm.getSetting().getStringValue());
            }
        }

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("roles", guild.getRoles());
        model.addAttribute("channels", guild.getTextChannels());
        model.addAttribute("commands", SQLSession.getSqlConnector().getSqlWorker().getAllSettings(guild.getId()).stream().filter(setting -> setting.getName().startsWith("com")).toList());
        model.addAttribute("prefixSetting", SQLSession.getSqlConnector().getSqlWorker().getSetting(guild.getId(), "chatprefix"));
        model.addAttribute("words", SQLSession.getSqlConnector().getSqlWorker().getChatProtectorWords(guild.getId()));

        List<Role> roles = new ArrayList<>();


        for (de.presti.ree6.sql.entities.roles.Role role : SQLSession.getSqlConnector().getSqlWorker().getAutoRoles(guild.getId())) {
            try {
                roles.add(guild.getRoleById(role.getRoleId()));
            } catch (Exception ignore) {
                SQLSession.getSqlConnector().getSqlWorker().removeAutoRole(guild.getId(), role.getRoleId());
            }
        }

        model.addAttribute("autoroles", roles);

        return MODERATION_PATH;
    }

    //endregion

    //region Social

    /**
     * Request Mapper for the Social Panel Page.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Session Identifier.
     * @param guildId             the ID of the selected Guild.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/social")
    public String openPanelSocial(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, guildId, id)) return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("roles", guild.getRoles());
        model.addAttribute("channels", guild.getTextChannels());

        if (SQLSession.getSqlConnector().getSqlWorker().isWelcomeSetup(guildId))
            model.addAttribute("welcomeChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(guildId).getChannelId())).findFirst().orElse(null));

        model.addAttribute("joinMessage", new Setting(guildId, "message_join", SQLSession.getSqlConnector().getSqlWorker().getMessage(guildId)));

        // Return to the Social Panel Page.
        return SOCIAL_PATH;
    }

    /**
     * Request Mapper for the Social Channel Change Panel.
     *
     * @param httpServletResponse the HTTP Response.
     * @param channelChangeForm   as the Form which contains the needed data.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @PostMapping(path = "/social/channel")
    public String openPanelSocial(HttpServletResponse httpServletResponse, @ModelAttribute(name = "channelChangeForm") ChannelChangeForm channelChangeForm, Model model) {

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, channelChangeForm.getGuild(), channelChangeForm.getIdentifier()))
            return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        // Change the channel Data.
        // Check if null.
        if (channelChangeForm.getChannel().equalsIgnoreCase("-1")) {
            if (channelChangeForm.getType().equalsIgnoreCase("welcomeChannel")) {
                Webhook webhookEntity = SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(guild.getId());
                // Delete the existing Webhook.
                guild.retrieveWebhooks().queue(webhooks -> webhooks.stream().filter(webhook -> webhook.getToken() != null).filter(webhook -> webhook.getId().equalsIgnoreCase(webhookEntity.getChannelId()) && webhook.getToken().equalsIgnoreCase(webhookEntity.getToken())).forEach(webhook -> webhook.delete().queue()));
            }
        } else {
            if (guild.getTextChannelById(channelChangeForm.getChannel()) != null) {
                if (channelChangeForm.getType().equalsIgnoreCase("welcomeChannel")) {
                    // Create new Webhook, If it has been created successfully add it to our Database.
                    Guild finalGuild = guild;
                    guild.getTextChannelById(channelChangeForm.getChannel()).createWebhook("Ree6-Welcome").queue(webhook -> SQLSession.getSqlConnector().getSqlWorker().setWelcomeWebhook(finalGuild.getId(), webhook.getId(), webhook.getToken()));
                }
            }
        }

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("roles", guild.getRoles());
        model.addAttribute("channels", guild.getTextChannels());

        if (SQLSession.getSqlConnector().getSqlWorker().isWelcomeSetup(guild.getId())) {
            Guild finalGuild1 = guild;
            model.addAttribute("welcomeChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(finalGuild1.getId()).getChannelId())).findFirst().orElse(null));
        }

        model.addAttribute("joinMessage", new Setting(guild.getId(), "message_join", SQLSession.getSqlConnector().getSqlWorker().getMessage(guild.getId())));

        return SOCIAL_PATH;
    }

    /**
     * Request Mapper for the Social Setting Change Panel.
     *
     * @param httpServletResponse the HTTP Response.
     * @param settingChangeForm   as the Form which contains the needed data.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @PostMapping(path = "/social/settings")
    public String openPanelSocial(HttpServletResponse httpServletResponse, @ModelAttribute(name = "settingChangeForm") SettingChangeForm settingChangeForm, Model model) {

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, settingChangeForm.getGuild(), settingChangeForm.getIdentifier()))
            return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        if (settingChangeForm.getSetting().getName() == null || settingChangeForm.getSetting().getValue() == null)
            return ERROR_400_PATH;

        // Change the setting Data.
        if (!settingChangeForm.getSetting().getName().equalsIgnoreCase("message_join")) {
            SQLSession.getSqlConnector().getSqlWorker().setSetting(settingChangeForm.getSetting());
        } else {
            SQLSession.getSqlConnector().getSqlWorker().setMessage(settingChangeForm.getGuild(), settingChangeForm.getSetting().getStringValue());
        }

        // Retrieve every Role and Channel of the Guild and set them as Attribute.
        model.addAttribute("roles", guild.getRoles());
        model.addAttribute("channels", guild.getTextChannels());

        if (SQLSession.getSqlConnector().getSqlWorker().isWelcomeSetup(guild.getId())) {
            Guild finalGuild = guild;
            model.addAttribute("welcomeChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getWelcomeWebhook(finalGuild.getId()).getChannelId())).findFirst().orElse(null));
        }

        model.addAttribute("joinMessage", new Setting(guild.getId(), "message_join", SQLSession.getSqlConnector().getSqlWorker().getMessage(guild.getId())));

        return SOCIAL_PATH;
    }

    //endregion

    //region Logging

    /**
     * Request Mapper for the Logging Panel Page.
     *
     * @param httpServletResponse the HTTP Response.
     * @param id                  the Session Identifier.
     * @param guildId             the ID of the selected Guild.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/logging")
    public String openPanelLogging(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "guildId") String guildId, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, guildId, id)) return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        // Retrieve every Log Option and Channel of the Guild and set them as Attribute.
        model.addAttribute("logs", SQLSession.getSqlConnector().getSqlWorker().getAllSettings(guild.getId()).stream().filter(setting -> setting.getName().startsWith("log")).toList());
        model.addAttribute("channels", guild.getTextChannels());

        if (SQLSession.getSqlConnector().getSqlWorker().isLogSetup(guildId))
            model.addAttribute("logChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(guildId).getChannelId())).findFirst().orElse(null));

        // Return to the Logging Panel Page.
        return LOGGING_PATH;
    }

    /**
     * Request Mapper for the Logging Channel Change Panel.
     *
     * @param httpServletResponse the HTTP Response.
     * @param channelChangeForm   as the Form which contains the needed data.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @PostMapping(path = "/logging/channel")
    public String openPanelLogging(HttpServletResponse httpServletResponse, @ModelAttribute(name = "channelChangeForm") ChannelChangeForm channelChangeForm, Model model) {

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, channelChangeForm.getGuild(), channelChangeForm.getIdentifier()))
            return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        // Change the channel Data.
        // Check if null.
        if (channelChangeForm.getChannel().equalsIgnoreCase("-1")) {
            Webhook webhookEntity = SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(guild.getId());
            // Delete the existing Webhook.
            guild.retrieveWebhooks().queue(webhooks -> webhooks.stream().filter(webhook -> webhook.getToken() != null).filter(webhook -> webhook.getId().equalsIgnoreCase(webhookEntity.getChannelId()) && webhook.getToken().equalsIgnoreCase(webhookEntity.getToken())).forEach(webhook -> webhook.delete().queue()));
        } else {
            if (channelChangeForm.getType().equalsIgnoreCase("logChannel") && guild.getTextChannelById(channelChangeForm.getChannel()) != null) {
                // Create new Webhook, If it has been created successfully add it to our Database.
                Guild finalGuild = guild;
                guild.getTextChannelById(channelChangeForm.getChannel()).createWebhook("Ree6-Logs").queue(webhook -> SQLSession.getSqlConnector().getSqlWorker().setLogWebhook(finalGuild.getId(), webhook.getId(), webhook.getToken()));
            }
        }

        // Retrieve every Log Option and Channel of the Guild and set them as Attribute.
        model.addAttribute("logs", SQLSession.getSqlConnector().getSqlWorker().getAllSettings(guild.getId()).stream().filter(setting -> setting.getName().startsWith("log")).toList());
        model.addAttribute("channels", guild.getTextChannels());
        if (SQLSession.getSqlConnector().getSqlWorker().isLogSetup(guild.getId())) {
            Guild finalGuild1 = guild;
            model.addAttribute("logChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(finalGuild1.getId()).getChannelId())).findFirst().orElse(null));
        }
        // Return to the Logging Panel Page.
        return LOGGING_PATH;
    }

    /**
     * Request Mapper for the Logging Setting Change Panel.
     *
     * @param httpServletResponse the HTTP Response.
     * @param settingChangeForm   as the Form which contains the needed data.
     * @param model               the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @PostMapping(path = "/logging/settings")
    public String openPanelLogging(HttpServletResponse httpServletResponse, @ModelAttribute(name = "settingChangeForm") SettingChangeForm settingChangeForm, Model model) {

        // Set default Data and If there was an error return to the Error Page.
        if (setDefaultInformation(model, httpServletResponse, settingChangeForm.getGuild(), settingChangeForm.getIdentifier()))
            return ERROR_403_PATH;

        // Get the Guild from the Model.
        Guild guild = null;

        if (model.getAttribute("guild") instanceof Guild guild1) guild = guild1;

        // If null return to Error page.
        if (guild == null) return ERROR_404_PATH;

        if (settingChangeForm.getSetting().getName() == null || settingChangeForm.getSetting().getValue() == null)
            return ERROR_400_PATH;

        // Change the setting Data.
        SQLSession.getSqlConnector().getSqlWorker().setSetting(settingChangeForm.getSetting());
        // Retrieve every Log Option and Channel of the Guild and set them as Attribute.
        model.addAttribute("logs", SQLSession.getSqlConnector().getSqlWorker().getAllSettings(guild.getId()).stream().filter(setting -> setting.getName().startsWith("log")).toList());
        model.addAttribute("channels", guild.getTextChannels());
        if (SQLSession.getSqlConnector().getSqlWorker().isLogSetup(guild.getId())) {
            Guild finalGuild = guild;
            model.addAttribute("logChannel", guild.getTextChannels().stream().filter(x -> x.getId().equalsIgnoreCase(SQLSession.getSqlConnector().getSqlWorker().getLogWebhook(finalGuild.getId()).getChannelId())).findFirst().orElse(null));
        }
        // Return to the Logging Panel Page.
        return LOGGING_PATH;
    }

    //endregion

    //region Recording

    /**
     * Request Mapper for the Server selection Panel.
     *
     * @param id    the Session Identifier.
     * @param model the ViewModel.
     * @return {@link String} for Thyme to the HTML Page.
     */
    @GetMapping(path = "/recording")
    public String openRecordingView(HttpServletResponse httpServletResponse, @CookieValue(name = "identifier", defaultValue = "-1") String id, @RequestParam(name = "recordId") String recordIdentifier, Model model) {

        // Check and decode the Identifier saved in the Cookies.
        id = SessionUtil.getIdentifier(id);

        if (SessionUtil.checkIdentifier(id)) {
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            deleteSessionCookie(httpServletResponse);
            return ERROR_403_PATH;
        }

        Session session = null;
        OAuth2User user;
        List<OAuth2Guild> guilds;

        try {
            // Try retrieving the Session from the Identifier.
            session = Server.getInstance().getOAuth2Client().getSessionController().getSession(id);

            // Set the Identifier.
            model.addAttribute("identifier", id);

            model.addAttribute("isLogged", true);

            Recording recording = SQLSession.getSqlConnector().getSqlWorker().getEntity(new Recording(), "SELECT * FROM Recording WHERE ID=:id", Map.of("id", recordIdentifier));

            if (recording == null) {
                model.addAttribute("errorCode", 404);
                model.addAttribute("errorMessage", "Couldn't find the requested Recording!");
                return ERROR_404_PATH;
            }

            // Try retrieving the Guilds of the OAuth2 User.
            guilds = Server.getInstance().getOAuth2Client().getGuilds(session).complete();

            user = Server.getInstance().getOAuth2Client().getUser(session).complete();

            if (guilds.stream().map(OAuth2Guild::getId).anyMatch(g -> g.equalsIgnoreCase(recording.getGuildId()))) {
                boolean found = false;

                for (JsonElement element : recording.getJsonArray()) {
                    if (found) break;

                    if (element.isJsonPrimitive()) {
                        JsonPrimitive primitive = element.getAsJsonPrimitive();
                        if (primitive.isString() && primitive.getAsString().equalsIgnoreCase(user.getId())) {
                            found = true;
                        }
                    }
                }

                if (found) {
                    model.addAttribute("guild", BotWorker.getShardManager().getGuildById(recording.getGuildId()));
                    model.addAttribute("recording", Base64.getEncoder().encodeToString(recording.getRecording()));
                    SQLSession.getSqlConnector().getSqlWorker().deleteEntity(recording);
                    return RECORDING_PATH;
                } else {
                    model.addAttribute("errorCode", 403);
                    model.addAttribute("errorMessage", "You are not allowed to view this Recording!");
                    return ERROR_403_PATH;
                }
            } else {
                model.addAttribute("errorCode", 403);
                model.addAttribute("errorMessage", "You are not allowed to access this Recording!");
                return ERROR_403_PATH;
            }
        } catch (Exception e) {
            // If the Session isn't null give the User a Notification that his Guilds couldn't be loaded.
            model.addAttribute("errorCode", 403);
            model.addAttribute("errorMessage", "Couldn't load Session!");
            if (session == null) {
                // If the Session is null just return to the default Page.
                deleteSessionCookie(httpServletResponse);
            }
            return ERROR_403_PATH;
        }
    }

    //endregion

    //region Utility

    /**
     * Set default information such as the Session Identifier and {@link Guild} Entity.
     *
     * @param model               the View Model.
     * @param httpServletResponse the HTTP Response.
     * @param guildId             the ID of the Guild
     * @param identifier          the Session Identifier.
     * @return true, if there was an error | false, if everything was alright.
     */
    public boolean setDefaultInformation(Model model, HttpServletResponse httpServletResponse, String guildId, String identifier) {
        try {
            // Try retrieving the Session from the Identifier.
            Session session = Server.getInstance().getOAuth2Client().getSessionController().getSession(identifier);

            if (session == null) {
                deleteSessionCookie(httpServletResponse);
                return true;
            }

            model.addAttribute("isLogged", true);

            // Try retrieving the User from the Session.
            OAuth2User oAuth2User = Server.getInstance().getOAuth2Client().getUser(session).complete();

            // Retrieve the Guild by its giving ID.
            Guild guild = BotWorker.getShardManager().getGuildById(guildId);

            // If the Guild couldn't be loaded redirect to Error page.
            if (guild == null) return true;

            Member member = guild.retrieveMemberById(oAuth2User.getId()).complete();

            if (member != null && member.hasPermission(Permission.ADMINISTRATOR)) {
                // Set the Guild.
                model.addAttribute("guild", guild);

                // Set the Identifier.
                model.addAttribute("identifier", identifier);
            } else {
                return true;
            }

            return false;
        } catch (Exception ignore) {
        }

        return true;
    }

    /**
     * Delete a Session Cookie that has been set.
     *
     * @param httpServletResponse the HTTP Response.
     */
    public void deleteSessionCookie(HttpServletResponse httpServletResponse) {
        Cookie cookie = new Cookie("identifier", null);

        cookie.setHttpOnly(true);
        cookie.setMaxAge(0);
        if (BotWorker.getVersion() != BotVersion.DEVELOPMENT_BUILD) cookie.setSecure(true);
        cookie.setPath("/");

        httpServletResponse.addCookie(cookie);
    }

    //endregion
}
