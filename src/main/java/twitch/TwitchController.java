package twitch;

import basemod.ReflectionHacks;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import battleaimod.BattleAiMod;
import battleaimod.networking.AiClient;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePostfixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpirePrefixPatch;
import com.evacipated.cardcrawl.modthespire.lib.SpireReturn;
import com.gikk.twirk.Twirk;
import com.gikk.twirk.types.users.TwitchUser;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.AsyncSaver;
import com.megacrit.cardcrawl.helpers.File;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.relics.Sozu;
import com.megacrit.cardcrawl.relics.WingBoots;
import com.megacrit.cardcrawl.screens.GameOverScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.ui.buttons.ReturnToMenuButton;
import ludicrousspeed.LudicrousSpeedMod;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;

public class TwitchController implements PostUpdateSubscriber, PostRenderSubscriber {
    private static final long DECK_DISPLAY_TIMEOUT = 300_000;
    private static final long BOSS_DISPLAY_TIMEOUT = 60_000;

    private static final long NO_VOTE_TIME_MILLIS = 1_000;
    private static final long FAST_VOTE_TIME_MILLIS = 3_000;
    private static final long NORMAL_VOTE_TIME_MILLIS = 20_000;

    public enum VoteType {
        // THe first vote in each dungeon
        CHARACTER("character", 25_000),
        MAP_LONG("map_long", 30_000),
        MAP_SHORT("map_short", 15_000),
        CARD_SELECT_LONG("card_select_long", 30_000),
        CARD_SELECT_SHORT("card_select_short", 20_000),
        GAME_OVER("game_over", 15_000),
        OTHER("other", 25_000),
        REST("rest", 1_000),
        SKIP("skip", 1_000);

        String optionName;
        int defaultTime;

        VoteType(String optionName, int defaultTime) {
            this.optionName = optionName;
            this.defaultTime = defaultTime;
        }
    }

    /**
     * Used to count user votes during
     */
    private HashMap<String, String> voteByUsernameMap = null;
    private VoteType currentVote = null;

    private String screenType = null;
    static VoteController voteController;

    HashMap<String, Integer> optionsMap;

    private long voteEndTimeMillis;

    private ArrayList<Choice> choices;
    ArrayList<Choice> viableChoices;
    private HashMap<String, Choice> choicesMap;

    private final LinkedBlockingQueue<String> readQueue;
    private final Twirk twirk;

    private boolean shouldStartClientOnUpdate = false;
    private boolean inBattle = false;
    private boolean fastMode = true;
    int consecutiveNoVotes = 0;

    public static long lastDeckDisplayTimestamp = 0L;
    public static long lastBossDisplayTimestamp = 0L;

    public TwitchController(LinkedBlockingQueue<String> readQueue, Twirk twirk) {
        this.readQueue = readQueue;
        this.twirk = twirk;

        optionsMap = new HashMap<>();
        optionsMap.put("asc", 0);

        for (VoteType voteType : VoteType.values()) {
            optionsMap.put(voteType.optionName, voteType.defaultTime);
        }
    }

    @Override
    public void receivePostUpdate() {
        if (shouldStartClientOnUpdate) {
            shouldStartClientOnUpdate = false;
            inBattle = true;
            startAiClient();
        }

        if (BattleAiMod.rerunController != null || LudicrousSpeedMod.mustRestart) {
            if (BattleAiMod.rerunController.isDone || LudicrousSpeedMod.mustRestart) {
                LudicrousSpeedMod.controller = BattleAiMod.rerunController = null;
                inBattle = false;
                if (LudicrousSpeedMod.mustRestart) {
                    System.err.println("Desync detected, rerunning simluation");
                    LudicrousSpeedMod.mustRestart = false;
                    startAiClient();
                }
            }
        }

        try {
            if (voteByUsernameMap != null) {
                long timeRemaining = voteEndTimeMillis - System.currentTimeMillis();

                if (timeRemaining <= 0) {
                    Choice result = getVoteResult();

                    System.err.println("selected " + result);

                    for (String command : result.resultCommands) {
                        if (currentVote == VoteType.CHARACTER &&
                                optionsMap.getOrDefault("asc", 0) > 0 &&
                                result.resultCommands.size() == 1) {
                            command += String.format(" %d", optionsMap.get("asc"));
                        }
                        readQueue.add(command);
                    }

                    voteByUsernameMap = null;
                    voteController = null;
                    currentVote = null;
                    screenType = null;
                }
            }
        } catch (NullPointerException e) {
            System.err.println("Null pointer caught, clean up this crap");
        }
    }

    public void receiveMessage(TwitchUser user, String message) {
        String userName = user.getDisplayName();
        String[] tokens = message.split(" ");

        if (tokens.length == 1 && tokens[0].equals("07734")) {
            fastMode = false;
            consecutiveNoVotes = 0;
        }

        if (userName.equalsIgnoreCase("twitchslaysspire")) {
            // admin direct command override
            if (tokens.length >= 2 && tokens[0].equals("!sudo")) {
                String command = message.substring(message.indexOf(' ') + 1);
                readQueue.add(command);
            } else if (tokens.length >= 2 && tokens[0].equals("!admin")) {
                if (tokens[1].equals("set")) {
                    if (tokens.length >= 4) {
                        String optionName = tokens[2];
                        if (optionsMap.containsKey(optionName)) {
                            try {
                                int optionValue = Integer.parseInt(tokens[3]);
                                optionsMap.put(optionName, optionValue);
                                System.err
                                        .format("%s successfully set to %d\n", optionName, optionValue);
                            } catch (NumberFormatException e) {

                            }
                        }
                    }
                }
            }
        }

        if (tokens.length == 1) {
            try {
                if (tokens[0].equals("!deck")) {
                    long now = System.currentTimeMillis();
                    if (now > lastDeckDisplayTimestamp + DECK_DISPLAY_TIMEOUT) {
                        lastDeckDisplayTimestamp = now;
                        twirk.channelMessage("[BOT] " + AbstractDungeon.player.masterDeck.group
                                .stream()
                                .map(card -> card.name)
                                .collect(Collectors
                                        .joining(";")));
                    }
                }

                if (tokens[0].equals("!boss")) {
                    long now = System.currentTimeMillis();
                    if (now > lastBossDisplayTimestamp + BOSS_DISPLAY_TIMEOUT) {
                        lastBossDisplayTimestamp = now;
                        twirk.channelMessage("[BOT] " + AbstractDungeon.bossKey);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        if (voteByUsernameMap != null) {
            if (tokens.length == 1 || (tokens.length >= 2 && VOTE_PREFIXES.contains(tokens[0]))) {
                String voteValue = tokens[0].toLowerCase();
                if (tokens.length >= 2 && VOTE_PREFIXES.contains(tokens[0])) {
                    voteValue = tokens[1].toLowerCase();
                }

                // remove leading 0s
                try {
                    voteValue = Integer.toString(Integer.parseInt(voteValue));
                } catch (NumberFormatException e) {
                }

                if (choicesMap.containsKey(voteValue)) {
                    voteByUsernameMap.put(userName, voteValue);
                }
            }
        }
    }

    public void startVote(String stateMessage) {
        JsonObject stateJson = new JsonParser().parse(stateMessage).getAsJsonObject();
        if (stateJson.has("available_commands")) {
            JsonArray availableCommandsArray = stateJson.get("available_commands").getAsJsonArray();

            Set<String> availableCommands = new HashSet<>();
            availableCommandsArray.forEach(command -> availableCommands.add(command.getAsString()));

            if (!inBattle) {
                if (availableCommands.contains("choose")) {
                    startChooseVote(stateJson);
                } else if (availableCommands.contains("play")) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    shouldStartClientOnUpdate = true;
                } else if (availableCommands.contains("start")) {
                    startCharacterVote();
                } else if (availableCommands.contains("proceed")) {
                    String screenType = stateJson.get("game_state").getAsJsonObject()
                                                 .get("screen_type").getAsString();
                    delayProceed(screenType);
                } else if (availableCommands.contains("confirm")) {
                    System.err.println("choosing confirm");
                    readQueue.add("confirm");
                } else if (availableCommands.contains("leave")) {
                    // exit shop hell
                    readQueue.add("leave");
                    readQueue.add("proceed");
                }
            }
        }
    }

    public void startChooseVote(JsonObject stateJson) {
        if (stateJson.has("game_state")) {
            VoteType voteType = VoteType.OTHER;

            JsonArray choicesJson = stateJson.get("game_state").getAsJsonObject().get("choice_list")
                                             .getAsJsonArray();
            choices = new ArrayList<>();
            choicesJson.forEach(choice -> {
                String choiceString = choice.getAsString();
                String choiceCommand = String.format("choose %s", choices.size());

                // the voteString will start at 1
                String voteString = Integer.toString(choices.size() + 1);

                Choice toAdd = new Choice(choiceString, voteString, choiceCommand);
                choices.add(toAdd);

            });
            viableChoices = getTrueChoices();

            screenType = stateJson.get("game_state").getAsJsonObject().get("screen_type")
                                  .getAsString();

//            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID && shouldDedupeGrid()) {
//                HashMap<String, Choice> choicesDedupe = new HashMap<>();
//                viableChoices.forEach(choice -> {
//                    if (!choicesDedupe.containsKey(choice.choiceName))
//                        choicesDedupe.put(choice.choiceName, choice);
//                });
//                viableChoices = new ArrayList<>(choicesDedupe.values());
//                viableChoices.sort(Comparator.comparing(c -> {
//                    try {
//                        return String.format("%03d", Integer.parseInt(c.voteString));
//                    } catch (NumberFormatException e) {
//                    }
//                    return c.voteString;
//                }));
//
//                for (int i = 0; i < viableChoices.size(); i++) {
//                    viableChoices.get(i).voteString = Integer.toString(i + 1);
//                }
//            }

            choicesMap = new HashMap<>();
            for (Choice choice : viableChoices) {
                choicesMap.put(choice.voteString, choice);
            }

            if (screenType != null) {
                if (screenType.equalsIgnoreCase("EVENT")) {
                    voteController = new EventVoteController(this);
                } else if (screenType.equalsIgnoreCase("MAP")) {
                    if (FIRST_FLOOR_NUMS.contains(AbstractDungeon.floorNum)) {
                        voteType = VoteType.MAP_LONG;
                    } else if (NO_OPT_REST_SITE
                            .contains(AbstractDungeon.floorNum) && !AbstractDungeon.player.relics
                            .stream()
                            .anyMatch(relic -> relic instanceof WingBoots && relic.counter > 0)) {
                        voteType = VoteType.SKIP;
                    } else {
                        voteType = VoteType.MAP_SHORT;
                    }

                    voteController = new MapVoteController(this);
                } else if (screenType.equalsIgnoreCase("SHOP_SCREEN")) {
                    voteController = new ShopScreenVoteController(this);
                } else if (screenType.equalsIgnoreCase("CARD_REWARD")) {
                    if (AbstractDungeon.floorNum == 1) {
                        voteType = VoteType.CARD_SELECT_LONG;
                    } else {
                        voteType = VoteType.CARD_SELECT_SHORT;
                    }

                    voteController = new CardRewardVoteController(this);
                } else if (screenType.equalsIgnoreCase("COMBAT_REWARD")) {
                    voteController = new CombatRewardVoteController(this);
                } else if (screenType.equalsIgnoreCase("REST")) {
                    voteController = new RestVoteController(this);
                } else if (screenType.equalsIgnoreCase("BOSS_REWARD")) {
                    voteController = new BossRewardVoteController(this);
                } else if (screenType.equals("GRID")) {
                    voteController = new GridVoteController(this);
                } else {
                    System.err.println("Starting generic vote for " + screenType);
                }
            }
            startVote(voteType);
        } else {
            System.err.println("ERROR Missing game state");
        }
    }

    public void delayProceed(String screenType) {
        choices = new ArrayList<>();

        choices.add(new Choice("proceed", "proceed", "proceed"));

        viableChoices = choices;

        choicesMap = new HashMap<>();
        for (Choice choice : viableChoices) {
            choicesMap.put(choice.voteString, choice);
        }

        VoteType voteType = VoteType.SKIP;

        if (screenType.equals("REST")) {
            voteType = VoteType.REST;
        } else if (screenType.equals("COMBAT_REWARD")) {
            voteType = VoteType.SKIP;
        } else if (screenType.equals("GAME_OVER")) {
            switch (AbstractDungeon.screen) {
                case DEATH:
                    ReturnToMenuButton deathReturnButton = ReflectionHacks
                            .getPrivate(AbstractDungeon.deathScreen, GameOverScreen.class, "returnButton");
                    deathReturnButton.hb.clicked = true;
                    break;
                case VICTORY:
                    ReturnToMenuButton victoryReturnButton = ReflectionHacks
                            .getPrivate(AbstractDungeon.victoryScreen, GameOverScreen.class, "returnButton");
                    victoryReturnButton.hb.clicked = true;
                    break;
            }
            System.err.println(AbstractDungeon.screen);
            voteType = VoteType.GAME_OVER;
        } else {
            System.err.println("unknown screen type proceed timer " + screenType);
        }

        System.err.println("delaying for " + screenType + " " + voteType);

        startVote(voteType, true);
    }

    public void startCharacterVote() {
        choices = new ArrayList<>();

        choices.add(new Choice("ironclad", "1", "start ironclad"));
        choices.add(new Choice("silent", "2", "start silent"));
        choices.add(new Choice("defect", "3", "start defect"));
        choices.add(new Choice("watcher", "4", "start watcher"));

        viableChoices = choices;

        choicesMap = new HashMap<>();
        for (Choice choice : viableChoices) {
            choicesMap.put(choice.voteString, choice);
        }

        voteController = new CharacterVoteController(this);

        startVote(VoteType.CHARACTER);
    }

    private void startVote(VoteType voteType, boolean forceWait) {
        voteByUsernameMap = new HashMap<>();
        currentVote = voteType;
        voteEndTimeMillis = System.currentTimeMillis();

        if (viableChoices.isEmpty()) {
            viableChoices.add(new Choice("proceed", "proceed", "proceed"));
        }

        if (viableChoices.size() > 1 || forceWait) {
            voteEndTimeMillis += fastMode ? FAST_VOTE_TIME_MILLIS : optionsMap
                    .get(voteType.optionName);
        } else {
            voteEndTimeMillis += NO_VOTE_TIME_MILLIS;
        }
    }

    private void startVote(VoteType voteType) {
        startVote(voteType, false);
    }

    @Override
    public void receivePostRender(SpriteBatch spriteBatch) {
        String topMessage = "";
        if (voteByUsernameMap != null && viableChoices != null && viableChoices.size() > 1) {
            if (voteController != null) {
                voteController.render(spriteBatch);
            } else {
                BitmapFont font = FontHelper.buttonLabelFont;
                String displayString = buildDisplayString();

                float timerMessageHeight = FontHelper.getHeight(font) * 5;

                FontHelper
                        .renderFont(spriteBatch, font, displayString, 15, Settings.HEIGHT * 7 / 8 - timerMessageHeight, Color.RED);
            }

            long remainingTime = voteEndTimeMillis - System.currentTimeMillis();

            topMessage += String
                    .format("Vote Time Remaining: %s", remainingTime / 1000 + 1);

        }
        if (fastMode) {
            topMessage += "\nDemo Mode (Random picks) type 07734 in chat to start playing";
        }

        if (!topMessage.isEmpty()) {
            BitmapFont font = FontHelper.buttonLabelFont;
            FontHelper
                    .renderFont(spriteBatch, font, topMessage, 15, Settings.HEIGHT * 7 / 8, Color.RED);
        }
    }

    private String buildDisplayString() {
        String result = "";
        HashMap<String, Integer> voteFrequencies = getVoteFrequencies();

        for (int i = 0; i < viableChoices.size(); i++) {
            Choice choice = viableChoices.get(i);

            result += String
                    .format("%s [vote %s] (%s)",
                            choice.choiceName,
                            choice.voteString,
                            voteFrequencies.getOrDefault(choice.voteString, 0));

            if (i < viableChoices.size() - 1) {
                result += "\n";
            }
        }

        return result;
    }

    private void startAiClient() {
        if (BattleAiMod.aiClient == null) {
            try {
                BattleAiMod.aiClient = new AiClient();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        if (BattleAiMod.aiClient != null) {
            BattleAiMod.aiClient.sendState();
        }
    }

    private ArrayList<Choice> getTrueChoices() {
        ArrayList<Choice> result = new ArrayList<>();

        boolean hasSozu = AbstractDungeon.player.relics.stream()
                                                       .anyMatch(relic -> relic instanceof Sozu);

        boolean hasPotionSlot = AbstractDungeon.player.potions.stream()
                                                              .anyMatch(potion -> potion instanceof PotionSlot);
        boolean canTakePotion = hasPotionSlot && !hasSozu;

        choices.stream()
               .filter(choice -> canTakePotion || !isPotionChoice(choice))
               .forEach(choice -> result.add(choice));

        if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
            result.add(new Choice("leave", "0", "leave", "proceed"));
        } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
            result.add(new Choice("Skip", "0", "skip", "proceed"));
        } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
            Optional<Choice> goldChoice = result.stream()
                                                .filter(choice -> choice.choiceName.equals("gold"))
                                                .findAny();
            if (goldChoice.isPresent()) {
                ArrayList<Choice> onlyGold = new ArrayList<>();
                onlyGold.add(goldChoice.get());

                // In the reward screen, always take the gold first if the option exists
                return onlyGold;
            }

            Optional<Choice> potionChoice = result.stream()
                                                  .filter(choice -> choice.choiceName
                                                          .equals("potion"))
                                                  .findAny();

            if (potionChoice.isPresent()) {
                ArrayList<Choice> onlyPotion = new ArrayList<>();
                onlyPotion.add(potionChoice.get());

                // Then the potion
                return onlyPotion;
            }

            Optional<Choice> relicChoice = result.stream()
                                                 .filter(choice -> choice.choiceName
                                                         .equals("relic"))
                                                 .findAny();

            Optional<Choice> sapphireKeyChoice = result.stream()
                                                       .filter(choice -> choice.choiceName
                                                               .equals("sapphire_key"))
                                                       .findAny();

            if (relicChoice.isPresent() && !sapphireKeyChoice.isPresent()) {
                ArrayList<Choice> onlyRelic = new ArrayList<>();
                onlyRelic.add(relicChoice.get());

                // Then the relic, as long as there's no key
                return onlyRelic;
            }

            Optional<Choice> stolenGoldChoice = result.stream()
                                                      .filter(choice -> choice.choiceName
                                                              .equals("stolen_gold"))
                                                      .findAny();

            if (stolenGoldChoice.isPresent()) {
                ArrayList<Choice> onlyStolenGold = new ArrayList<>();
                onlyStolenGold.add(stolenGoldChoice.get());

                // Then the stolen gold
                return onlyStolenGold;
            }

            Optional<Choice> emeraldKeyChoice = result.stream()
                                                      .filter(choice -> choice.choiceName
                                                              .equals("emerald_key"))
                                                      .findAny();

            if (emeraldKeyChoice.isPresent()) {
                ArrayList<Choice> onlyEmeraldKey = new ArrayList<>();
                onlyEmeraldKey.add(emeraldKeyChoice.get());

                // Then the emerald key
                return onlyEmeraldKey;
            }
        }

        return result;
    }

    public static class Choice {
        final String choiceName;
        String voteString;
        final ArrayList<String> resultCommands;

        public Choice(String choiceName, String voteString, String... resultCommands) {
            this.choiceName = choiceName;
            this.voteString = voteString;

            this.resultCommands = new ArrayList<>();
            for (String resultCommand : resultCommands) {
                this.resultCommands.add(resultCommand);
            }
        }

        @Override
        public String toString() {
            return "Choice{" +
                    "choiceName='" + choiceName + '\'' +
                    ", voteString='" + voteString + '\'' +
                    ", resultCommands=" + resultCommands +
                    '}';
        }
    }


    boolean shouldDedupeGrid() {
        GridCardSelectScreen gridSelectScreen = AbstractDungeon.gridSelectScreen;
        int numCards = ReflectionHacks
                .getPrivate(gridSelectScreen, GridCardSelectScreen.class, "numCards");
        if (numCards != 1) {
            return false;
        }

        return gridSelectScreen.forPurge || gridSelectScreen.forUpgrade || gridSelectScreen.forTransform;
    }

    private static boolean isPotionChoice(Choice choice) {
        if (choice.choiceName.equals("Fire Potion")) {
            return true;
        }

        boolean potionLibraryMatch = POTION_NAMES.contains(choice.choiceName.toLowerCase());

        return potionLibraryMatch || choice.choiceName.toLowerCase().contains("potion");
    }

    public static HashSet<String> POTION_NAMES = new HashSet<String>() {{
        add("distilled chaos");
        add("entropic brew");
        add("smoke bomb");
        add("snecko oil");
        add("liquid memories");
        add("essence of steel");
        add("liquid bronze");
        add("ambrosia");
        add("bottled miracle");
        add("ghost in a jar");
        add("heart of iron");
        add("essence of darkness");
        add("blessing of the forge");
        add("fruit juice");
    }};

    public static HashSet<String> VOTE_PREFIXES = new HashSet<String>() {{
        add("!vote");
        add("vote");
    }};

    public static HashSet<Integer> FIRST_FLOOR_NUMS = new HashSet<Integer>() {{
        add(0);
        add(17);
        add(34);
    }};

    public static HashSet<Integer> NO_OPT_REST_SITE = new HashSet<Integer>() {{
        add(14);
        add(31);
        add(48);
    }};

    HashMap<String, Integer> getVoteFrequencies() {
        if (voteByUsernameMap == null) {
            return new HashMap<>();
        }

        HashMap<String, Integer> frequencies = new HashMap<>();

        voteByUsernameMap.entrySet().forEach(entry -> {
            String choice = entry.getValue();
            if (!frequencies.containsKey(choice)) {
                frequencies.put(choice, 0);
            }

            frequencies.put(choice, frequencies.get(choice) + 1);
        });

        return frequencies;
    }

    private Choice getVoteResult() {
        HashMap<String, Integer> frequencies = getVoteFrequencies();

        Set<Map.Entry<String, Integer>> entries = frequencies.entrySet();
        if (voteByUsernameMap.size() == 0) {
            if (viableChoices.size() > 1) {
                consecutiveNoVotes++;
                if (consecutiveNoVotes >= 5) {
                    fastMode = true;
                }

                System.err.println("choosing random for no votes");
            }

            int randomResult = new Random().nextInt(viableChoices.size());

            return viableChoices.get(randomResult);
        } else {
            consecutiveNoVotes = 0;
        }

        ArrayList<String> bestResults = new ArrayList<>();
        int bestRate = 0;

        for (Map.Entry<String, Integer> entry : entries) {
            if (entry.getValue() > bestRate) {
                bestResults = new ArrayList<>();
                bestResults.add(entry.getKey());
                bestRate = entry.getValue();
            } else if (entry.getValue() == bestRate) {
                bestResults.add(entry.getKey());
            }
        }
        String bestResult = bestResults.get(new Random().nextInt(bestResults.size()));

        if (!choicesMap.containsKey(bestResult.toLowerCase())) {
            System.err.println("choosing random for invalid votes " + bestResult);
            int randomResult = new Random().nextInt(viableChoices.size());
            return viableChoices.get(randomResult);
        }

        return choicesMap.get(bestResult.toLowerCase());
    }

    @SpirePatch(clz = GridCardSelectScreen.class, method = "updateCardPositionsAndHoverLogic")
    public static class GridRenderPatch {
        @SpirePrefixPatch
        public static SpireReturn messWithGridSelect(GridCardSelectScreen gridCardSelectScreen) {
            if (TwitchController.voteController != null && TwitchController.voteController instanceof GridVoteController) {
                ArrayList<AbstractCard> cards = gridCardSelectScreen.targetGroup.group;

                int lineNum = 0;
                for (int i = 0; i < cards.size(); i++) {
                    int mod = i % 8;
                    if (mod == 0 && i != 0) {
                        ++lineNum;
                    }

                    AbstractCard card = cards.get(i);

                    float drawStartX = ReflectionHacks
                            .getPrivate(gridCardSelectScreen, GridCardSelectScreen.class, "drawStartX");
                    float drawStartY = ReflectionHacks
                            .getPrivate(gridCardSelectScreen, GridCardSelectScreen.class, "drawStartY");
                    float currentDiffY = ReflectionHacks
                            .getPrivate(gridCardSelectScreen, GridCardSelectScreen.class, "currentDiffY");

                    float padX = ReflectionHacks
                            .getPrivate(gridCardSelectScreen, GridCardSelectScreen.class, "padX");
                    float padY = ReflectionHacks
                            .getPrivate(gridCardSelectScreen, GridCardSelectScreen.class, "padY");


                    card.drawScale = .45F;
                    card.target_x = card.current_x = drawStartX + (float) mod * (padX / 2.F * 1.5F) - 300;
                    card.target_y = card.current_y = drawStartY + currentDiffY - (float) lineNum * (padY / 2.F * 1.4F) + 75;

                    AbstractDungeon.overlayMenu.cancelButton.hide();
                }

                return SpireReturn.Return(null);
            }
            return SpireReturn.Continue();
        }
    }

    @SpirePatch(clz = AsyncSaver.class, method = "save")
    public static class BackUpAllSavesPatch {
        @SpirePostfixPatch
        public static void backUpSave(String filePath, String data) {
            BlockingQueue<File> saveQueue = ReflectionHacks
                    .getPrivateStatic(AsyncSaver.class, "saveQueue");

            String backupFilePath = String
                    .format("savealls\\%s_%02d_%s", filePath, AbstractDungeon.floorNum, Settings.seed);

            saveQueue.add(new File(backupFilePath, data));
        }
    }
}
