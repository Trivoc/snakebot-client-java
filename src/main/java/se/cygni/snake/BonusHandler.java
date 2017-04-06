package se.cygni.snake;

import se.cygni.snake.api.model.SnakeDirection;

import java.util.*;

/**
 * Created by trivo on 2017-03-31.
 */
public class BonusHandler {



    private Map<SnakeDirection, BonusTracker> bonusMap;

    public BonusHandler(){
        bonusMap = new EnumMap<>(SnakeDirection.class);
    }

    public BonusTracker addBonusTracker(SnakeDirection dir){
        BonusTracker bt = new BonusTracker();
        bonusMap.put(dir, bt);
        return bt;
    }

    public SnakeDirection getBestBonus(){
        Collection<SnakeDirection> directions = bonusMap.keySet();

        int maxValue = Integer.MIN_VALUE;
        SnakeDirection bestBonus = SnakeDirection.DOWN;

        for(SnakeDirection dir : directions){
            BonusTracker bt = bonusMap.get(dir);
            int bonusValue = bt.getFoodOnPath() - bt.getNearCollisions();
            System.out.println("Direction " + dir + " has bonus value " + bonusValue);
            if(bonusValue > maxValue){
                maxValue = bonusValue;
                bestBonus = dir;
            }
        }
        return bestBonus;
    }

    public int getBonus(SnakeDirection dir){
        BonusTracker bt = bonusMap.get(dir);
        return bt.getFoodOnPath()  + bt.getKillBonus() + bt.getMiddleBonus() + bt.getFreeHeadSpaces();
    }




}
