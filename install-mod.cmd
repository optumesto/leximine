@echo off
rem Installs Leximine into Minecraft as its own launcher profile.
rem One time, unless you rebuild the mod and want the new jar in place.
title Install Leximine into Minecraft
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0scripts\install_mod.ps1" %*
if errorlevel 1 pause
