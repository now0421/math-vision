\## Claude Code exploration rule



Do not use Task tool, subagents, or Explore agents for repository exploration.



When planning or analyzing code, use only direct read-only tools such as Read, Grep, Glob, LS, and safe Bash commands like git grep, find, rg, and git status.



If subagents fail once, do not retry subagents. Continue with direct sequential file inspection.



In plan mode, do not launch parallel Explore agents.

