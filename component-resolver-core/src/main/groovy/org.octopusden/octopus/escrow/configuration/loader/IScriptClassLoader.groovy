package org.octopusden.octopus.escrow.configuration.loader

interface IScriptClassLoader {
    Class loadScript(String scriptName);
 }
