#!/usr/bin/env bash
# One-time preparation of a fresh Ubuntu 24.04 Azure VM for the ProGenie back end. Safe to run again.
#   sudo bash ~/progenie/ProGenie/infra/nocard/azure/setup.sh
# Adds 2 GB of swap (the free VM sizes have 1 GB of RAM), automatic security updates and Docker.
set -euo pipefail
[ "$(id -u)" = 0 ] || { echo "Run with sudo: sudo bash $0" >&2; exit 1; }
user=${SUDO_USER:-azureuser}

if ! swapon --show | grep -q '^/swapfile'; then
  echo "Adding 2 GB of swap..."
  fallocate -l 2G /swapfile
  chmod 600 /swapfile
  mkswap /swapfile >/dev/null
  swapon /swapfile
  grep -q '^/swapfile ' /etc/fstab || echo '/swapfile none swap sw 0 0' >> /etc/fstab
fi
echo 'vm.swappiness=10' > /etc/sysctl.d/90-progenie-swap.conf
sysctl -q -p /etc/sysctl.d/90-progenie-swap.conf

echo "Installing updates and tools..."
export DEBIAN_FRONTEND=noninteractive NEEDRESTART_MODE=a
apt-get update -q
apt-get -y -q upgrade
apt-get -y -q install git unattended-upgrades ca-certificates curl

if ! command -v docker >/dev/null; then
  echo "Installing Docker..."
  curl -fsSL https://get.docker.com | sh
fi
usermod -aG docker "$user"

echo
echo "Done. Log out and back in (so '$user' can use Docker without sudo), then continue with the guide."
free -h | sed -n '1,3p'
