package se.cygni.snake;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.web.socket.WebSocketSession;
import se.cygni.snake.api.event.*;
import se.cygni.snake.api.exception.InvalidPlayerName;
import se.cygni.snake.api.model.*;
import se.cygni.snake.api.model.Map;
import se.cygni.snake.api.response.PlayerRegistered;
import se.cygni.snake.api.util.GameSettingsUtils;
import se.cygni.snake.client.AnsiPrinter;
import se.cygni.snake.client.BaseSnakeClient;
import se.cygni.snake.client.MapCoordinate;
import se.cygni.snake.client.MapUtil;

import java.util.*;

public class SimpleSnakePlayer extends BaseSnakeClient {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleSnakePlayer.class);

    // Set to false if you want to start the game from a GUI
    private static final boolean AUTO_START_GAME = false;

    // Personalise your game ...
    private static final String SERVER_NAME = "snake.cygni.se";
    private static  final int SERVER_PORT = 80;

    private static final GameMode GAME_MODE = GameMode.TOURNAMENT;
    private static final String SNAKE_NAME = "Solid Snakey";

    // Set to false if you don't want the game world printed every game tick.
    private static final boolean ANSI_PRINTER_ACTIVE = false;
    private AnsiPrinter ansiPrinter = new AnsiPrinter(ANSI_PRINTER_ACTIVE, true);


    //Final variables
    private SnakeState currentState;
    private static final int MAX_SEARCH_DEPTH = 30; //Play with this value for search depth


    private SnakeInfo[] snakes;
    private MapUtil mapUtil;
    private Map map;
    private MapUpdateEvent mue;
    private HashSet<MapCoordinate> foodSet;
    private int finalOpenSpaces;


    public static void main(String[] args) {
        SimpleSnakePlayer simpleSnakePlayer = new SimpleSnakePlayer();
        try {
            ListenableFuture<WebSocketSession> connect = simpleSnakePlayer.connect();
            connect.get();
        } catch (Exception e) {
            LOGGER.error("Failed to connect to server", e);
            System.exit(1);
        }

        startTheSnake(simpleSnakePlayer);
    }

    /**
     * The Snake client will continue to run ...
     * : in TRAINING mode, until the single game ends.
     * : in TOURNAMENT mode, until the server tells us its all over.
     */
    private static void startTheSnake(final SimpleSnakePlayer simpleSnakePlayer) {
        Runnable task = () -> {
            do {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while (simpleSnakePlayer.isPlaying());

            LOGGER.info("Shutting down");
        };

        Thread thread = new Thread(task);
        thread.start();
    }

     private void initiateState(){
         Snake self = null;
         ArrayList<Snake> foes = new ArrayList<>();
         foodSet = new HashSet<>();

         for(SnakeInfo snake : snakes){
             MapCoordinate[] spread = mapUtil.getSnakeSpread(snake.getId());
             if(snake.getId().equals(getPlayerId())){
                 self = new Snake(getPlayerId(), spread, 0);
                 self.setDir(SnakeDirection.DOWN);
             } else {
                 Snake foe = new Snake(snake.getId(), spread, 0);
                 foe.setDir(SnakeDirection.DOWN);
                 foes.add(foe);
             }
         }

         currentState = new SnakeState(map.getHeight(), map.getWidth(), self, foes,
                 mapUtil.listCoordinatesContainingObstacle());
     }

     private void updateState(){
         MapCoordinate newHead = mapUtil.getSnakeSpread(getPlayerId())[0];
         currentState.updateSnakeState(newHead, getFoeSnakes());
     }

     private HashSet<Snake> getFoeSnakes(){
         HashSet<Snake> foes = new HashSet<>();
         for(SnakeInfo snake : snakes){
             if(!snake.getId().equals(getPlayerId()) && snake.isAlive()){
                 String fId = snake.getId();
                 foes.add(new Snake(fId, mapUtil.getSnakeSpread(fId), mue.getGameTick()));
             }
         }
         return foes;
     }

     private void upDateInstance(MapUpdateEvent update){
         mapUtil = new MapUtil(update.getMap(), getPlayerId());
         map = update.getMap();
         mue = update;
         snakes = map.getSnakeInfos();
         foodSet = new HashSet<>();

         for(MapCoordinate food : mapUtil.listCoordinatesContainingFood()){
             foodSet.add(food);
         }
     }

    @Override
    public void onMapUpdate(MapUpdateEvent mapUpdateEvent) {

        System.out.println("=============== CURRENTLY AT STATE NUMBER " + mapUpdateEvent.getGameTick() + "===============");

        long millis = System.currentTimeMillis();

        upDateInstance(mapUpdateEvent);


        //Needed for multiple games with same instance
        if (mapUpdateEvent.getGameTick() > 0) {
            updateState();
        } else {
            initiateState();
        }

        System.out.println("Current position: " + currentState.getSelf().getHead());

        SnakeDirection bestDir = getBestPossibleDirection();

        if(foodSet.contains(getTileInFront(currentState.getSelf(), bestDir))){
            System.out.println("Found food!");
            currentState.getSelf().setHasEaten(true);
        }


        registerMove(mapUpdateEvent.getGameTick(), bestDir);
        System.out.println("Elapsed time: " + (System.currentTimeMillis() - millis));
    }


    private SnakeDirection getBestPossibleDirection(){

        EnumMap<SnakeDirection, Tuple<Integer, Integer>> results = new EnumMap<>(SnakeDirection.class);
        EnumMap<SnakeDirection, Integer> collisionRisk = new EnumMap<>(SnakeDirection.class);
        BonusHandler bh = new BonusHandler();

        SnakeDirection bestDir = SnakeDirection.DOWN;
        int maxValue = Integer.MIN_VALUE;
        int maxTiles = 0;
        int mostFinalOpenSpaces = 0;

        for(SnakeDirection dir : SnakeDirection.values()) {
            finalOpenSpaces = 0;
            if (currentState.canIMoveInDirection(dir)) {
                System.out.println("====" + dir + "====");
                BonusTracker bt = bh.addBonusTracker(dir);
                SnakeState futureState = currentState.createFutureState(dir);

                int searchVal = getLongestPossiblePath(futureState, bt, MAX_SEARCH_DEPTH);
                int openTiles = currentState.getOpenSpacesinDir(dir);

                collisionRisk.put(dir, getCollisionRisk(currentState, dir));

                if(searchVal > maxValue){
                    maxValue = searchVal;
                    bestDir = dir;
                    maxTiles = openTiles;
                    mostFinalOpenSpaces = finalOpenSpaces;

                } else if (finalOpenSpaces > mostFinalOpenSpaces && openTiles > maxValue){
                    bestDir = dir;
                    maxTiles = openTiles;
                    mostFinalOpenSpaces = finalOpenSpaces;
                } else if (openTiles > maxTiles){
                    //System.out.println("Best direction set to " + dir);
                    bestDir = dir;
                    maxTiles = openTiles;
                    mostFinalOpenSpaces = finalOpenSpaces;
                }

                System.out.println("Found a value of " + searchVal + " in direction " + dir + " with " + openTiles + " tiles");
                results.put(dir, new Tuple<>(searchVal, openTiles));

            }

        }

        int maxBonus = bh.getBonus(bestDir);
        for(SnakeDirection dir : results.keySet()){
            Tuple<Integer, Integer> resTuple = results.get(dir);
            if(dir != bestDir && resTuple.first >= maxValue && resTuple.second >= maxTiles && bh.getBonus(dir) > maxBonus){
                bestDir = dir;
                maxBonus = bh.getBonus(dir);
                System.out.println("Changed to " + dir + " due to bonuses");
            }
        }

        int leastRisk = collisionRisk.get(bestDir);
        if(leastRisk > 1){
            for(SnakeDirection dir : collisionRisk.keySet()){
                int colRisk = collisionRisk.get(dir);
                if(colRisk < leastRisk && (results.get(dir).first > maxValue*0.6)){
                    System.out.println("Changed to " + dir + " due to collision risk");
                    leastRisk = collisionRisk.get(dir);
                    bestDir = dir;

                }
            }
        }

        return bestDir;
    }

    private boolean isSelfMovingMid(SnakeState state){
        MapCoordinate selfHead = state.getSelf().getHead();
        SnakeDirection selfDir = state.getSelf().getDir();
        int width = state.getMapWidth();
        int height = state.getMapHeight();

        if(selfHead.x < width / 2) {
            if(selfHead.y < height / 2){ //First and third quadrant
                return selfDir == SnakeDirection.RIGHT ||selfDir == SnakeDirection.DOWN;
            } else
                return selfDir == SnakeDirection.RIGHT || selfDir == SnakeDirection.UP;
        } else {
            if(selfHead.y < height / 2){
                return selfDir == SnakeDirection.LEFT || selfDir == SnakeDirection.DOWN;
            } else {
                return selfDir == SnakeDirection.LEFT || selfDir == SnakeDirection.UP;
            }
        }
    }


    private int finalizePath(SnakeState state){
        if(state.canIMoveInDirection(state.getSelf().getDir())){
            finalOpenSpaces = state.getOpenSpacesinDir(state.getSelf().getDir());
            return 0;
        } else {
            for (SnakeDirection dir : SnakeDirection.values()) {
                if (state.canIMoveInDirection(dir)) {
                    int spaces = state.getOpenSpacesinDir(dir);
                    if (spaces > finalOpenSpaces) {
                        finalOpenSpaces = spaces;
                    }
                }
            }
        }
        return 0;
    }

    private int getLongestPossiblePath(SnakeState state, BonusTracker bt, int depth){
        if(depth <= 0){
            return finalizePath(state);
        }

        checkBonusValue(state, bt, depth);

        SnakeDirection currentDir = state.getSelf().getDir();
        if(state.canIMoveInDirection(currentDir)){
            return 1 + getLongestPossiblePath(state.createFutureState(currentDir), bt, depth-1);
        } else {
            int mostOpenSpaces = 0;
            SnakeDirection bestDir = null;
            for(SnakeDirection dir : SnakeDirection.values()){
                int openSpaces = state.getOpenSpacesinDir(dir);
                if(state.canIMoveInDirection(dir) && openSpaces > mostOpenSpaces){
                    //System.out.println("Expected turnout when predicting bend: " + dir + ": " + openSpaces);
                    mostOpenSpaces = openSpaces;
                    bestDir = dir;

                }
            }

            if(bestDir != null){
                return 1 + getLongestPossiblePath(state.createFutureState(bestDir), bt, depth-1);
            }

            for(SnakeDirection dir : SnakeDirection.values()){
                if(state.canIMoveInDirection(dir)){
                    finalOpenSpaces = state.getOpenSpacesinDir(state.getSelf().getDir());
                }
            }
            //System.out.println("Path ended: Returning with final open spaces " +  finalOpenSpaces);
            return 0;
        }
    }



    private void checkBonusValue(SnakeState state, BonusTracker bt, int depth){

        if(state.getFoes().size() > 1){
            if(!isHeadWrapped(state) && depth >= MAX_SEARCH_DEPTH - 10){
                bt.headFree();
            }

            if(depth >= MAX_SEARCH_DEPTH - 20 && foodSet.contains(state.getSelf().getHead())){
                bt.foodFound(15);
            }

            if(depth >= MAX_SEARCH_DEPTH - 2 && isSelfMovingMid(state)) {
                bt.targetMiddle();
            }
        }



        if(depth >= MAX_SEARCH_DEPTH - 10 && state.getIsKilledFoeState()){
            //System.out.println("Predicting kill in " + (depth - MAX_SEARCH_DEPTH) + " steps");
            bt.killBonus();
        }

    }

    private int getCollisionRisk(SnakeState state, SnakeDirection dir){
        int highRisk = 3 * getHighRiskValue(state, dir);
        int lowRisk = getLowRiskValue(state, dir);
        return highRisk + lowRisk;
    }

    //Both getLowriskValue and getHighRiskValue are ugly as sin. Oh well.
    private int getLowRiskValue(SnakeState state, SnakeDirection dir){
        int lowRiskValue = 0;
        HashMap<MapCoordinate, SnakeDirection> riskPosition = new HashMap<>();
        MapCoordinate selfHead = state.getSelf().getHead();
        switch (dir){
            case LEFT:
                riskPosition.put(selfHead.translateBy(-3, 0), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(-2, 1), null);
                riskPosition.put(selfHead.translateBy(-2, -1), null);
                riskPosition.put(selfHead.translateBy(-1, -2), SnakeDirection.UP);
                riskPosition.put(selfHead.translateBy(-1, 2), SnakeDirection.DOWN);
                break;
            case RIGHT:
                riskPosition.put(selfHead.translateBy(3, 0), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(2, 1), null);
                riskPosition.put(selfHead.translateBy(2, -1), null);
                riskPosition.put(selfHead.translateBy(1, -2), SnakeDirection.UP);
                riskPosition.put(selfHead.translateBy(1, 2), SnakeDirection.DOWN);
                break;
            case DOWN:
                riskPosition.put(selfHead.translateBy(0, 3), SnakeDirection.DOWN);
                riskPosition.put(selfHead.translateBy(-2, 1), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(2, 1), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(1, 2), null);
                riskPosition.put(selfHead.translateBy(-1, 2),null);
                break;
            case UP:
                riskPosition.put(selfHead.translateBy(0, -3), SnakeDirection.UP);
                riskPosition.put(selfHead.translateBy(-2, -1), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(2, -1), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(1, -2), null);
                riskPosition.put(selfHead.translateBy(-1, -2),null);
                break;

        }
        for (Snake foe : state.getFoes()){
            MapCoordinate foeHead = foe.getHead();
            for(MapCoordinate riskPos : riskPosition.keySet()){
                if(foeHead.equals(riskPos) && foe.getDir() != riskPosition.get(foeHead)){
                    System.out.println("Low risk for collision detected");
                    lowRiskValue++;
                }
            }
        }
        return lowRiskValue;
    }

    private int getHighRiskValue(SnakeState state, SnakeDirection dir){
        int highRiskValue = 0;
        HashMap<MapCoordinate, SnakeDirection> riskPosition = new HashMap<>();
        MapCoordinate selfHead = state.getSelf().getHead();
        switch (dir){
            case LEFT:
                riskPosition.put(selfHead.translateBy(-2, 0), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(-1, 1), SnakeDirection.DOWN);
                riskPosition.put(selfHead.translateBy(-1, -1), SnakeDirection.UP);
                break;
            case RIGHT:
                riskPosition.put(selfHead.translateBy(2, 0), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(1, 1), SnakeDirection.DOWN);
                riskPosition.put(selfHead.translateBy(1, -1), SnakeDirection.UP);
                break;
            case DOWN:
                riskPosition.put(selfHead.translateBy(-1, 1), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(1, 1), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(0, 2), SnakeDirection.DOWN);
                break;
            case UP:
                riskPosition.put(selfHead.translateBy(-1, -1), SnakeDirection.LEFT);
                riskPosition.put(selfHead.translateBy(1, -1), SnakeDirection.RIGHT);
                riskPosition.put(selfHead.translateBy(0, -2), SnakeDirection.UP);
                break;

        }
        for (Snake foe : state.getFoes()){
            MapCoordinate foeHead = foe.getHead();
            for(MapCoordinate riskPos : riskPosition.keySet()){
                if(foeHead.equals(riskPos) && foe.getDir() != riskPosition.get(foeHead)){
                    System.out.println("High risk for collision detected!");
                    highRiskValue++;
                }
            }
        }
        return highRiskValue;
    }

    private boolean isHeadWrapped(SnakeState state){
        Snake self = state.getSelf();
        MapCoordinate selfHead = self.getHead();
        SnakeDirection selfDir = self.getDir();
        HashSet<MapCoordinate> selfBody = self.getBodySet();
        HashSet<MapCoordinate> blockades = state.getTotalSet();
        blockades.removeAll(selfBody);

        if(selfDir == SnakeDirection.DOWN || selfDir == SnakeDirection.UP){
            return (blockades.contains(selfHead.translateBy(-1, 0))) ||
                    blockades.contains(selfHead.translateBy(1, 0)) ||
                    selfHead.x == 45 || selfHead.x == 0;
        } else {
            return (blockades.contains(selfHead.translateBy(0, 1))) ||
                    blockades.contains(selfHead.translateBy(0, -1)) ||
                    selfHead.y == 33 || selfHead.y == 0;
        }
    }


    private MapCoordinate getTileInFront(Snake snake, SnakeDirection dir){
        switch(dir) {
            case RIGHT:
                return snake.getHead().translateBy(1, 0);
            case LEFT:
                return snake.getHead().translateBy(-1, 0);
            case DOWN:
                return snake.getHead().translateBy(0, 1);
            case UP:
                return snake.getHead().translateBy(0, -1);
            default:
                return snake.getHead();
        }

    }
    

    @Override
    public void onInvalidPlayerName(InvalidPlayerName invalidPlayerName) {
        LOGGER.debug("InvalidPlayerNameEvent: " + invalidPlayerName);
    }

    @Override
    public void onSnakeDead(SnakeDeadEvent snakeDeadEvent) {
        LOGGER.info("A snake {} died by {}",
                snakeDeadEvent.getPlayerId(),
                snakeDeadEvent.getDeathReason());
    }

    @Override
    public void onGameResult(GameResultEvent gameResultEvent) {
        LOGGER.info("Game result:");
        gameResultEvent.getPlayerRanks().forEach(playerRank -> LOGGER.info(playerRank.toString()));
    }

    @Override
    public void onGameEnded(GameEndedEvent gameEndedEvent) {
        LOGGER.debug("GameEndedEvent: " + gameEndedEvent);
    }

    @Override
    public void onGameStarting(GameStartingEvent gameStartingEvent) {
        LOGGER.debug("GameStartingEvent: " + gameStartingEvent);
    }

    @Override
    public void onPlayerRegistered(PlayerRegistered playerRegistered) {
        LOGGER.info("PlayerRegistered: " + playerRegistered);

        if (AUTO_START_GAME) {
            startGame();
        }
    }

    @Override
    public void onTournamentEnded(TournamentEndedEvent tournamentEndedEvent) {
        LOGGER.info("Tournament has ended, winner playerId: {}", tournamentEndedEvent.getPlayerWinnerId());
        int c = 1;
        for (PlayerPoints pp : tournamentEndedEvent.getGameResult()) {
            LOGGER.info("{}. {} - {} points", c++, pp.getName(), pp.getPoints());
        }
    }

    @Override
    public void onGameLink(GameLinkEvent gameLinkEvent) {
        LOGGER.info("The game can be viewed at: {}", gameLinkEvent.getUrl());
    }

    @Override
    public void onSessionClosed() {
        LOGGER.info("Session closed");
    }

    @Override
    public void onConnected() {
        LOGGER.info("Connected, registering for training...");
        GameSettings gameSettings = GameSettingsUtils.trainingWorld();
        registerForGame(gameSettings);
    }

    @Override
    public String getName() {
        return SNAKE_NAME;
    }

    @Override
    public String getServerHost() {
        return SERVER_NAME;
    }

    @Override
    public int getServerPort() {
        return SERVER_PORT;
    }

    @Override
    public GameMode getGameMode() {
        return GAME_MODE;
    }
}
