package twitch;

import basemod.ReflectionHacks;
import basemod.interfaces.PostRenderSubscriber;
import basemod.interfaces.PostUpdateSubscriber;
import battleaimod.BattleAiMod;
import battleaimod.networking.AiClient;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.core.Settings;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.helpers.FontHelper;
import com.megacrit.cardcrawl.helpers.Hitbox;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.potions.PotionSlot;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import com.megacrit.cardcrawl.relics.Sozu;
import com.megacrit.cardcrawl.rewards.RewardItem;
import com.megacrit.cardcrawl.screens.CardRewardScreen;
import com.megacrit.cardcrawl.screens.select.GridCardSelectScreen;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.shop.StorePotion;
import com.megacrit.cardcrawl.shop.StoreRelic;
import com.megacrit.cardcrawl.ui.buttons.LargeDialogOptionButton;
import com.megacrit.cardcrawl.ui.buttons.SingingBowlButton;
import com.megacrit.cardcrawl.ui.buttons.SkipCardButton;
import com.megacrit.cardcrawl.ui.campfire.AbstractCampfireOption;
import communicationmod.ChoiceScreenUtils;
import ludicrousspeed.LudicrousSpeedMod;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.LinkedBlockingQueue;

public class TwitchController implements PostUpdateSubscriber, PostRenderSubscriber {
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
        OTHER("other", 15_000);

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

    // Event rendering references
    HashMap<String, LargeDialogOptionButton> messageToEventButtonMap = null;
    HashMap<String, String> messageToOriginalEventButtonMessageMap = null;

    // Map Rendering references
    HashMap<String, MapRoomNode> messageToRoomNodeMap = null;

    // Shop Screen rending references
    HashMap<String, Object> messageToShopItemMap = null;

    // Card reward rendering references
    HashMap<String, AbstractCard> messageToCardReward = null;

    // Reward Item
    HashMap<String, RewardItem> messageToCombatRewardItem = null;
    HashMap<String, String> messageToOriginalRewardTextMap = null;

    // Rest
    HashMap<String, AbstractCampfireOption> messageToRestOption = null;

    // Boss relic
    HashMap<String, AbstractRelic> messageToBossRelicMap;

    HashMap<String, Integer> optionsMap;

    private long voteEndTimeMillis;

    private ArrayList<Choice> choices;
    private ArrayList<Choice> viableChoices;
    private HashMap<String, Choice> choicesMap;

    LinkedBlockingQueue<String> readQueue;

    private boolean shouldStartClientOnUpdate = false;
    private boolean inBattle = false;
    private boolean fastMode = true;
    int consecutiveNoVotes = 0;

    public TwitchController(LinkedBlockingQueue<String> readQueue) {
        this.readQueue = readQueue;

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

        if (BattleAiMod.battleAiController != null || LudicrousSpeedMod.mustRestart) {
            if (BattleAiMod.battleAiController.isDone || LudicrousSpeedMod.mustRestart) {
                LudicrousSpeedMod.controller = BattleAiMod.battleAiController = null;
                inBattle = false;
                if (LudicrousSpeedMod.mustRestart) {
                    System.err.println("Desync detected, rerunning simluation");
                    LudicrousSpeedMod.mustRestart = false;
                    startAiClient();
                }
            }
        }

        if (voteByUsernameMap != null) {
            long timeRemaining = voteEndTimeMillis - System.currentTimeMillis();

            if (timeRemaining <= 0) {
                Choice result = getVoteResult();

                for (String command : result.resultCommands) {
                    if (currentVote == VoteType.CHARACTER &&
                            optionsMap.getOrDefault("asc", 0) > 0 &&
                            result.resultCommands.size() == 1) {
                        command += String.format(" %d", optionsMap.get("asc"));
                    }
                    readQueue.add(command);
                }

                voteByUsernameMap = null;
                currentVote = null;
                screenType = null;
                messageToEventButtonMap = null;
                messageToShopItemMap = null;
                messageToCardReward = null;
                messageToRoomNodeMap = null;
                messageToCombatRewardItem = null;
                messageToRestOption = null;
                messageToBossRelicMap = null;
                messageToOriginalRewardTextMap = null;
            }
        }
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

        String bestResult = "";
        int bestRate = 0;

        for (Map.Entry<String, Integer> entry : entries) {
            if (entry.getValue() > bestRate) {
                bestResult = entry.getKey();
                bestRate = entry.getValue();
            }
        }

        if (!choicesMap.containsKey(bestResult.toLowerCase())) {
            System.err.println("choosing random for invalid votes " + bestResult);
            int randomResult = new Random().nextInt(viableChoices.size());
            return viableChoices.get(randomResult);
        }

        return choicesMap.get(bestResult.toLowerCase());
    }

    private HashMap<String, Integer> getVoteFrequencies() {
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

    public void receiveMessage(String userName, String message) {
        String[] tokens = message.split(" ");
        if (tokens.length >= 2 && tokens[0].equals("!set")) {
            if (tokens[1].equals("slow")) {
                fastMode = false;
                consecutiveNoVotes = 0;
            }
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
                    readQueue.add("proceed");
                } else if (availableCommands.contains("confirm")) {
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
                Choice toAdd = new Choice(choiceString, Integer
                        .toString(choices.size()), choiceCommand);
                choices.add(toAdd);

            });
            viableChoices = getTrueChoices();

            screenType = stateJson.get("game_state").getAsJsonObject().get("screen_type")
                                  .getAsString();

            if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.GRID && shouldDedupeGrid()) {
                HashMap<String, Choice> choicesDedupe = new HashMap<>();
                viableChoices.forEach(choice -> {
                    if (!choicesDedupe.containsKey(choice.choiceName))
                        choicesDedupe.put(choice.choiceName, choice);
                });
                viableChoices = new ArrayList<>(choicesDedupe.values());
                viableChoices.sort(Comparator.comparing(c -> {
                    try {
                        return String.format("%03d", Integer.parseInt(c.voteString));
                    } catch (NumberFormatException e) {
                    }
                    return c.voteString;
                }));

                for (int i = 0; i < viableChoices.size(); i++) {
                    viableChoices.get(i).voteString = Integer.toString(i);
                }
            }

            choicesMap = new HashMap<>();
            for (Choice choice : viableChoices) {
                choicesMap.put(choice.voteString, choice);
            }

            if (screenType != null) {
                if (screenType.equalsIgnoreCase("EVENT")) {
                    messageToEventButtonMap = new HashMap<>();
                    ArrayList<LargeDialogOptionButton> eventButtons = ChoiceScreenUtils
                            .getEventButtons();
                    messageToOriginalEventButtonMessageMap = new HashMap<>();
                    eventButtons.forEach(button -> {
                        messageToOriginalEventButtonMessageMap.put(ChoiceScreenUtils
                                .getOptionName(button.msg.toLowerCase()), button.msg);
                        messageToEventButtonMap.put(ChoiceScreenUtils
                                .getOptionName(button.msg.toLowerCase()), button);
                    });
                } else if (screenType.equalsIgnoreCase("MAP")) {
                    if (FIRST_FLOOR_NUMS.contains(AbstractDungeon.floorNum)) {
                        voteType = VoteType.MAP_LONG;
                    } else {
                        voteType = VoteType.MAP_SHORT;
                    }

                    messageToRoomNodeMap = new HashMap<>();
                    ArrayList<MapRoomNode> mapChoice = ChoiceScreenUtils.getMapScreenNodeChoices();
                    for (MapRoomNode node : mapChoice) {
                        messageToRoomNodeMap
                                .put(String.format("x=%d", node.x).toLowerCase(), node);
                    }
                } else if (screenType.equalsIgnoreCase("SHOP_SCREEN")) {
                    messageToShopItemMap = new HashMap<>();
                    ArrayList<Object> shopItems = ReflectionHacks
                            .privateStaticMethod(ChoiceScreenUtils.class, "getAvailableShopItems")
                            .invoke();
                    for (Object item : shopItems) {
                        messageToShopItemMap.put(getShopItemString(item).toLowerCase(), item);
                    }
                } else if (screenType.equalsIgnoreCase("CARD_REWARD")) {
                    if (AbstractDungeon.floorNum == 1) {
                        voteType = VoteType.CARD_SELECT_LONG;
                    } else {
                        voteType = VoteType.CARD_SELECT_SHORT;
                    }

                    messageToCardReward = new HashMap<>();
                    for (AbstractCard card : AbstractDungeon.cardRewardScreen.rewardGroup) {
                        messageToCardReward.put(card.name.toLowerCase(), card);
                    }
                } else if (screenType.equalsIgnoreCase("COMBAT_REWARD")) {
                    messageToCombatRewardItem = new HashMap<>();
                    messageToOriginalRewardTextMap = new HashMap<>();
                    for (RewardItem reward : AbstractDungeon.combatRewardScreen.rewards) {
                        messageToCombatRewardItem.put(reward.type.name().toLowerCase(), reward);
                        messageToOriginalRewardTextMap
                                .put(reward.type.name().toLowerCase(), reward.text);
                    }
                } else if (screenType.equalsIgnoreCase("REST")) {
                    messageToRestOption = new HashMap<>();

                    ArrayList<AbstractCampfireOption> restOptions = ReflectionHacks
                            .privateStaticMethod(ChoiceScreenUtils.class, "getValidRestRoomButtons")
                            .invoke();

                    for (AbstractCampfireOption restOption : restOptions) {
                        messageToRestOption.put(getCampfireOptionName(restOption), restOption);
                    }
                } else if (screenType.equalsIgnoreCase("BOSS_REWARD")) {
                    messageToBossRelicMap = new HashMap<>();

                    for (AbstractRelic relic : AbstractDungeon.bossRelicScreen.relics) {
                        messageToBossRelicMap.put(relic.name.toLowerCase(), relic);
                    }
                }
            }


            startVote(voteType);
        } else {
            System.err.println("ERROR Missing game state");
        }
    }

    public void startCharacterVote() {
        choices = new ArrayList<>();

        choices.add(new Choice("Ironclad", "ironclad", "start ironclad"));
        choices.add(new Choice("Silent", "silent", "start silent"));
        choices.add(new Choice("Defect", "defect", "start defect"));

        viableChoices = choices;

        choicesMap = new HashMap<>();
        for (Choice choice : viableChoices) {
            choicesMap.put(choice.voteString, choice);
        }

        startVote(VoteType.CHARACTER);
    }

    private void startVote(VoteType voteType) {
        voteByUsernameMap = new HashMap<>();
        currentVote = voteType;
        voteEndTimeMillis = System.currentTimeMillis();

        if (viableChoices.isEmpty()) {
            viableChoices.add(new Choice("proceed", "proceed", "proceed"));
        }

        if (viableChoices.size() > 1) {
            voteEndTimeMillis += fastMode ? FAST_VOTE_TIME_MILLIS : optionsMap
                    .get(voteType.optionName);
        } else {
            voteEndTimeMillis += NO_VOTE_TIME_MILLIS;
        }
    }

    @Override
    public void receivePostRender(SpriteBatch spriteBatch) {
        String topMessage = "";
        if (voteByUsernameMap != null && viableChoices != null && viableChoices.size() > 1) {
            HashMap<String, Integer> voteFrequencies = getVoteFrequencies();
            if (messageToEventButtonMap != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (messageToEventButtonMap.containsKey(message)) {
                        messageToEventButtonMap.get(message).msg = String
                                .format("%s [vote %s] (%s)",
                                        messageToOriginalEventButtonMessageMap.get(message),
                                        choice.voteString,
                                        voteFrequencies.getOrDefault(choice.voteString, 0));
                    } else {
                        System.err.println("no event button for " + choice.choiceName);
                    }
                }
            } else if (messageToRoomNodeMap != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (messageToRoomNodeMap.containsKey(message)) {
                        MapRoomNode mapRoomNode = messageToRoomNodeMap.get(message);
                        Hitbox roomHitbox = mapRoomNode.hb;

                        String mapMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        // Alternate having the vote above and below so that the messages don't
                        // run into each other
                        if (i % 2 == 0) {
                            renderTextBelowHitbox(spriteBatch, mapMessage, roomHitbox);
                        } else {
                            renderTextAboveHitbox(spriteBatch, mapMessage, roomHitbox);
                        }
                    } else {
                        System.err.println("no room button for " + choice.choiceName);
                    }
                }
            } else if (messageToShopItemMap != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (message.equals("leave")) {
                        String leaveMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        renderTextBelowHitbox(spriteBatch, leaveMessage, AbstractDungeon.overlayMenu.cancelButton.hb);
                    } else if (message.equals("purge")) {
                        String purgeMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        renderTextBelowHitbox(spriteBatch, purgeMessage, addGoldHitbox(getShopPurgeHitbox(), 1));
                    } else if (messageToShopItemMap.containsKey(message)) {
                        Hitbox shopItemHitbox = getShopItemHitbox(messageToShopItemMap
                                .get(message));


                        if (shopItemHitbox != null) {
                            String shopMessage = String.format("[vote %s] (%s)",
                                    choice.voteString,
                                    voteFrequencies.getOrDefault(choice.voteString, 0));

                            renderTextBelowHitbox(spriteBatch, shopMessage, shopItemHitbox);
                        } else {
                            System.err.println("no hitbox for" + choice.choiceName);
                        }
                    } else {
                        System.err.println("no shop button for " + choice.choiceName);
                    }
                }
            } else if (messageToCardReward != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (message.equalsIgnoreCase("skip")) {
                        String skipMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        SkipCardButton skipCardButton = ReflectionHacks
                                .getPrivate(AbstractDungeon.cardRewardScreen, CardRewardScreen.class, "skipButton");

                        renderTextBelowHitbox(spriteBatch, skipMessage, cardRewardAdjust(skipCardButton.hb));
                    } else if (message.equalsIgnoreCase("bowl")) {
                        String bowlMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        SingingBowlButton bowlButton = ReflectionHacks
                                .getPrivate(AbstractDungeon.cardRewardScreen, CardRewardScreen.class, "bowlButton");

                        renderTextBelowHitbox(spriteBatch, bowlMessage, cardRewardAdjust(bowlButton.hb));
                    } else if (messageToCardReward.containsKey(message)) {
                        AbstractCard card = messageToCardReward.get(message);
                        Hitbox cardHitbox = card.hb;
                        String cardMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        renderTextBelowHitbox(spriteBatch, cardMessage, cardRewardAdjust(cardHitbox));
                    } else {
                        System.err.println("no card button for " + choice.choiceName);
                    }
                }
            } else if (messageToCombatRewardItem != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (messageToCombatRewardItem.containsKey(message)) {
                        RewardItem rewardItem = messageToCombatRewardItem.get(message);
                        String rewardItemMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        rewardItem.text = messageToOriginalRewardTextMap
                                .get(message) + rewardItemMessage;

                    } else {
                        System.err.println("no card button for " + choice.choiceName);
                    }
                }
            } else if (messageToBossRelicMap != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName.toLowerCase(Locale.ROOT);
                    if (messageToBossRelicMap.containsKey(message)) {
                        AbstractRelic rewardItem = messageToBossRelicMap.get(message);
                        Hitbox rewardItemHitbox = rewardItem.hb;
                        String rewardItemMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        renderTextBelowHitbox(spriteBatch, rewardItemMessage, rewardItemHitbox);
                    } else {
                        System.err.println("no boss relic button for " + choice.choiceName);
                    }
                }
            } else if (messageToRestOption != null) {
                for (int i = 0; i < viableChoices.size(); i++) {
                    Choice choice = viableChoices.get(i);

                    String message = choice.choiceName;
                    if (messageToRestOption.containsKey(message)) {
                        AbstractCampfireOption fireOption = messageToRestOption.get(message);
                        Hitbox hitbox = fireOption.hb;
                        String voteMessage = String.format("[vote %s] (%s)",
                                choice.voteString,
                                voteFrequencies.getOrDefault(choice.voteString, 0));

                        renderTextBelowHitbox(spriteBatch, voteMessage, addGoldHitbox(hitbox, 1));
                    } else {
                        System.err.println("no boss relic button for " + choice.choiceName);
                    }
                }
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
            topMessage += "\nFast Mode active [!set slow] for more time";
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
            result.add(new Choice("leave", "leave", "leave", "proceed"));
        } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.CARD_REWARD) {
            result.add(new Choice("Skip", "skip", "skip", "proceed"));
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

    private static String getShopItemString(Object item) {
        if (item instanceof String) {
            return (String) item;
        } else if (item instanceof AbstractCard) {
            return ((AbstractCard) item).name.toLowerCase();
        } else if (item instanceof StoreRelic) {
            return ((StoreRelic) item).relic.name;
        } else if (item instanceof StorePotion) {
            return ((StorePotion) item).potion.name;
        }

        System.err.println("no string can be made for " + item);

        return "";
    }

    private static Hitbox getShopItemHitbox(Object item) {
        if (item instanceof String) {
            System.err.println("no hitbox for string " + item);
            return null;
        } else if (item instanceof AbstractCard) {
            return addGoldHitbox(((AbstractCard) item).hb, 1);
        } else if (item instanceof StoreRelic) {
            StoreRelic storeRelic = (StoreRelic) item;
            return addGoldHitbox(storeRelic.relic.hb, ReflectionHacks
                    .getPrivate(storeRelic, StoreRelic.class, "slot"));
        } else if (item instanceof StorePotion) {
            StorePotion storePotion = (StorePotion) item;
            return addGoldHitbox(storePotion.potion.hb, ReflectionHacks
                    .getPrivate(storePotion, StorePotion.class, "slot"));
        }

        System.err.println("no string can be made for " + item);

        return null;
    }

    private static Hitbox cardRewardAdjust(Hitbox hitbox) {
        return new Hitbox(hitbox.x, hitbox.y - 15.0F * Settings.scale, hitbox.width, hitbox.height + 25 * Settings.scale);
    }

    private static Hitbox addGoldHitbox(Hitbox hitbox, int slot) {
        return new Hitbox(hitbox.x + (slot - 1) * 50, hitbox.y - 62.0F * Settings.scale, hitbox.width, hitbox.height + 62.F * Settings.scale);
    }

    private static String getCampfireOptionName(AbstractCampfireOption option) {
        String classname = option.getClass().getSimpleName();
        String nameWithoutOption = classname.substring(0, classname.length() - "Option".length());
        return nameWithoutOption.toLowerCase();
    }

    private void renderTextBelowHitbox(SpriteBatch spriteBatch, String text, Hitbox hitbox) {
        BitmapFont font = FontHelper.buttonLabelFont;
        Color color = Color.RED;
        float textWidth = FontHelper.getWidth(font, text, 1f);
        float messageX = hitbox.x + (hitbox.width - textWidth) / 2;

        FontHelper.renderFont(spriteBatch, font, text, messageX, hitbox.y, color);
    }

    private void renderTextAboveHitbox(SpriteBatch spriteBatch, String text, Hitbox hitbox) {
        BitmapFont font = FontHelper.buttonLabelFont;
        Color color = Color.RED;
        float textWidth = FontHelper.getWidth(font, text, 1f);
        float messageX = hitbox.x + (hitbox.width - textWidth) / 2;

        float messageY = hitbox.y + hitbox.height + FontHelper.getHeight(font);

        FontHelper.renderFont(spriteBatch, font, text, messageX, messageY, color);
    }

    private Hitbox getShopPurgeHitbox() {
        ShopScreen screen = AbstractDungeon.shopScreen;
        float CARD_W = 110.0F * Settings.scale;
        float CARD_H = 150.0F * Settings.scale;
        float purgeCardX = ReflectionHacks.getPrivate(screen, ShopScreen.class, "purgeCardX");
        float purgeCardY = ReflectionHacks.getPrivate(screen, ShopScreen.class, "purgeCardY");

        float height = 2 * CARD_H;
        float width = 2 * CARD_W;

        return new Hitbox(purgeCardX - CARD_W, purgeCardY - CARD_H, width, height);
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
}
