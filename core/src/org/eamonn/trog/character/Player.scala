package org.eamonn.trog.character

import com.badlogic.gdx.Input.Keys
import com.badlogic.gdx.graphics.Color
import com.badlogic.gdx.graphics.g2d.PolygonSpriteBatch
import org.eamonn.trog.Trog.{Square, garbage}
import org.eamonn.trog.items.{HealingPotion, makeCommonWeapon}
import org.eamonn.trog.scenes.Game
import org.eamonn.trog.util.TextureWrapper
import org.eamonn.trog.{Actor, Enemy, Pathfinding, Vec2, d, screenUnit}

import scala.util.Random

case class Player() extends Actor {
  var inventoryItemSelected: Int = 0
  var archetype: Archetype = _
  var initialized = false
  var healing = 0f
  var healingFactor = 0.1f
  var lastStrike: String = "Unknown Forces"
  var equipment: Equipment = new Equipment
  var resting = false
  var name = ""
  var dead = false
  var exploring = false
  var stats: Stats = basePlayerStats()
  var inCombat = false
  var rangedSkillUsing: Option[rangedSkill] = None
  var rangedSkillTargetables: List[Actor] = List.empty
  var rangedSkillOption = 0
  def clearRangedStuff(): Unit = {
    rangedSkillUsing = None
    rangedSkillTargetables = List.empty
    rangedSkillOption = 0
    game.clickedForTargeting = false
  }
  var game: Game = _
  var location: Vec2 = Vec2(0, 0)
  var destination: Vec2 = Vec2(0, 0)
  var yourTurn = true
  var tick = 0f
  var speed = .25f
  var clickInInv = false
  var clickTick = 0f
  def initially(gme: Game): Unit = {
    game = gme
    val potion: HealingPotion = HealingPotion()
    potion.number = 10
    potion.possessor = Some(this)
    potion.game = game
    game.items = potion :: game.items
    archetype.onSelect(game)
    stats.health = stats.maxHealth
    initialized = true
  }
  def playerIcon: TextureWrapper =
    TextureWrapper.load(s"Player${archetype.metaArchName}.png")
  def levelUp(): Unit = {
    game.addMessage("You levelled up")
    stats.exp -= stats.nextExp
    stats.nextExp *= 2
    stats.maxHealth += d(2, 5)
    archetype.onLevelUp(game)
    stats.health = stats.maxHealth
    stats.level += 1
  }
  def tryToGoDown(): Unit = {
    if (location == game.level.downLadder) game.descending = true
  }
  def attack(target: Actor): Unit = {
    if (equipment.weapon.nonEmpty) {
      equipment.weapon.foreach(w => w.onAttack(this, target))
    } else {
      if (d(10) > target.stats.ac) {
        target.stats.health -= 1
      }
    }
  }
  def draw(batch: PolygonSpriteBatch) = {

    if (stats.health > 0) {
      batch.setColor(Color.RED)
      batch.draw(
        Square,
        location.x * screenUnit,
        location.y * screenUnit,
        screenUnit * stats.health / stats.maxHealth,
        screenUnit * .1f
      )
    }
    batch.setColor(Color.WHITE)
    batch.draw(
      playerIcon,
      location.x * screenUnit,
      location.y * screenUnit,
      screenUnit,
      screenUnit
    )
  }
  def update(delta: Float) = {
    if (!yourTurn) {
      tick += delta
      if (tick >= speed || resting || exploring) {
        yourTurn = true
        tick = 0f
      }
    }
    clickTick += delta
    if (clickTick > .325f) {
      clickTick = 0f
      clickInInv = false
    }
    if (stats.health <= 0) dead = true
    if (!game.inInventory && !game.inCharacterSheet) gameControl(delta)
    else if (game.inInventory && !clickInInv)
      clickInInv = inventoryControl(delta)
    else if (game.inCharacterSheet) charSheetControl(delta)
  }
  def gameControl(delta: Float) = {
    var initLoc = location.copy()
    if (inCombat) {
      destination = location.copy()
      exploring = false
    }
    if (healing > 4 && stats.health < stats.maxHealth) {
      stats.health += 1
      healing = 0
    }
    if (stats.exp >= stats.nextExp) {
      levelUp()
    }
    if (
      game.enemies.exists(e => {
        val path = Pathfinding.findPath(e.location, location, game.level)
        var dist = Int.MaxValue
        path.foreach(p => {
          dist = p.list.length
        })
        dist < stats.sightRad
      })
    ) {
      inCombat = true
    } else inCombat = false
    if (yourTurn) {
      if (statuses.stunned > 0) {
        statuses.stunned -= 1
        yourTurn = false
        game.enemyTurn = true
      } else {
        game.keysDown
          .find(key => Character.isDigit(Keys.toString(key).charAt(0)))
          .foreach(n => {
            var keyn = Keys.toString(n).toInt
            if (stats.skills.length >= keyn && keyn > 0)
              stats.skills(keyn - 1) match {
                case range: rangedSkill => {
                  if (range.ccd == 0) {
                    rangedSkillUsing = Some(range)
                  }
                }
                case melee: meleeSkill => {
                  if (melee.ccd == 0) {
                    melee
                      .selectTarget(game, this)
                      .foreach(t => {
                        melee.onUse(this, t, game)
                        melee.ccd = melee.coolDown
                        if (melee.takesTurn) {
                          yourTurn = false
                          game.enemyTurn = true
                        }
                      })
                  }
                }
              }
          })
        if (game.keysDown.contains(Keys.Z)) {
          exploring = !exploring
          if (!exploring) destination = location.copy()
        }
        if (
          exploring && destination == location && !game.level.walkables.forall(
            w => game.explored.contains(w)
          )
        ) {
          var dest =
            game.level.walkables
              .filter(w => !game.explored.contains(w))
              .minBy(w =>
                Pathfinding.findPath(location, w, game.level).head.list.length
              )
          destination = dest.copy()
        }
        if (game.level.walkables.forall(w => game.explored.contains(w)))
          exploring = false
        if (
          game.keysDown.contains(Keys.S) || game.keysDown.contains(Keys.DOWN)
        ) {
          destination.y = location.y - 1
          destination.x = location.x
        } else if (
          game.keysDown.contains(Keys.W) || game.keysDown.contains(Keys.UP)
        ) {
          destination.y = location.y + 1
          destination.x = location.x
        } else if (
          game.keysDown.contains(Keys.D) || game.keysDown.contains(Keys.RIGHT)
        ) {
          destination.y = location.y
          destination.x = location.x + 1
        } else if (
          game.keysDown.contains(Keys.A) || game.keysDown.contains(Keys.LEFT)
        ) {
          destination.y = location.y
          destination.x = location.x - 1
        } else if (game.keysDown.contains(Keys.SPACE)) {
          resting = true
          exploring = false
          game.enemyTurn = true
        } else if (game.clicked) {
          destination = game.mouseLocOnGrid.copy()
        } else if (game.keysDown.contains(Keys.R)) {
          resting = true
          exploring = false
        } else if (
          game.keysDown.contains(Keys.PERIOD) && (game.keysDown.contains(
            Keys.SHIFT_RIGHT
          ) || game.keysDown.contains(Keys.SHIFT_LEFT))
        ) {
          tryToGoDown()
        } else if (game.keysDown.contains(Keys.G)) {
          game.items.foreach(ite => {
            ite.location.foreach(l => {
              if (l == location) {
                ite.pickUp(this)
                game.addMessage(s"You picked up x${ite.number} ${ite.name}")
              }
            })
          })
        }
      }
      if ((destination != location || resting) && yourTurn) {
        if (!resting) {
          val path = Pathfinding.findPath(location, destination, game.level)
          path.foreach(p => {
            val dest = p.list.reverse(1).copy()
            val enemy = game.enemies.filter(e => e.location == dest)
            if (enemy.isEmpty) {
              location = dest.copy()
            } else {
              attack(enemy.head)
              destination = location.copy()
            }
          })
        } else {
          destination = location.copy()
          if (!inCombat && stats.health < stats.maxHealth)
            (healing += healingFactor * 10)
        }
        if (stats.health < stats.maxHealth) healing += healingFactor
        yourTurn = false
        if (initLoc != location) {
          getVisible = game.level.walkables
            .filter(w =>
              Pathfinding
                .findPath(location, w, game.level)
                .forall(p => p.list.length < stats.sightRad)
            )
        }

        getVisible
          .foreach(w => {
            if (!game.explored.contains(w)) {
              game.explored = w :: game.explored
            }
          })
        game.enemyTurn = true
      }
      if (resting && (stats.health == stats.maxHealth || inCombat)) {
        resting = false
      }
    }
  }
  var getVisible: List[Vec2] = List.empty
  def inventoryControl(delta: Float): Boolean = {
    var clicked = false
    var inventory = game.items
      .filter(i => i.possessor.contains(this))
      .filter(n => n.tNum >= 1)
    if (inventory.nonEmpty) {
      if (inventory.length < inventoryItemSelected) {
        inventoryItemSelected -= 1
      }

      if (game.keysDown.contains(Keys.DOWN) || game.keysDown.contains(Keys.S)) {
        inventoryItemSelected = (inventoryItemSelected + 1) % inventory.length
        clicked = true
      }
      if (game.keysDown.contains(Keys.UP) || game.keysDown.contains(Keys.W)) {
        inventoryItemSelected =
          (inventoryItemSelected + inventory.length - 1) % inventory.length
        clicked = true
      }
      if (yourTurn) {
        if (
          game.keysDown
            .contains(Keys.ENTER) || game.keysDown.contains(Keys.SPACE)
        ) {
          if (inventory.length >= inventoryItemSelected) {
            inventory(inventoryItemSelected).use(this)
          }
          clicked = true
          yourTurn = false
          game.enemyTurn = true
        }
      }
    }
    clicked
  }
  def charSheetControl(delta: Float) = {}
}
