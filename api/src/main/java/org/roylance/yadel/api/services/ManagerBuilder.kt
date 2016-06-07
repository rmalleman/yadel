package org.roylance.yadel.api.services

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Props
import com.typesafe.config.ConfigFactory
import org.roylance.yadel.api.actors.ManagerBase
import org.roylance.yadel.api.enums.CommonTokens
import org.roylance.yadel.api.models.YadelModels
import scala.Tuple2

class ManagerBuilder<T:ManagerBase>(
        private val hostName:String,
        private val port:String,
        private val managerClass:Class<T>):IBuilder<Tuple2<ActorSystem, ActorRef>> {
    override fun build(): Tuple2<ActorSystem, ActorRef> {
        val config = ConfigFactory.parseString(String.format(CommonTokens.TcpPortConifguration, this.port))
                        .withFallback(ConfigFactory.parseString(String.format(CommonTokens.TcpHostConfiguration, this.hostName)))
                        .withFallback(ConfigFactory.parseString(CommonTokens.ManagerConfiguration))
                        .withFallback(ConfigFactory.load())

        val managerSystem = ActorSystem.create(CommonTokens.ClusterName, config)
        val returnTuple = Tuple2(
                managerSystem,
                managerSystem.actorOf(Props.create(this.managerClass), YadelModels.ActorRole.MANAGER.name))

        return returnTuple
    }
}
