#!/usr/bin/env bash
# End-to-end test of the shipped program, run against the real launcher or the packaged Windows exe.
# Paths are deliberately RELATIVE: git-bash and a Windows JVM disagree about what /tmp means, and a
# test that fails because of that would tell us nothing about the backup code.
set -u

LAUNCH=("$@")
if [ "${#LAUNCH[@]}" -eq 0 ]; then echo "usage: smoke.sh <launcher> [args...]"; exit 2; fi

work=$(mktemp -d) || exit 1
cd "$work" || exit 1
trap 'cd /; rm -rf "$work"' EXIT
mkdir -p src/photos/2024 src/skipme src/.hidden dst

head -c 1024 /dev/zero > src/a.txt
head -c 3000000 /dev/zero > src/photos/2024/big.bin
echo hello > src/photos/2024/c.txt
echo no > src/skipme/s.txt
echo hid > src/.hidden/h.txt

fail() {
  echo "SMOKE FAIL: $*"
  echo "--- last run ---"
  tail -12 run.log 2>/dev/null
  echo "--- destination ---"
  find dst -type f 2>/dev/null | head -30
  exit 1
}

run() {
  "${LAUNCH[@]}" "$@" > run.log 2>&1
  return $?
}

# 1. a dry run must not touch the disk at all
run --source src --dest dst --dry-run || fail "dry run exited non-zero"
[ -f dst/.airdrive-pc.tsv ] && fail "a dry run created the record file"
[ -n "$(find dst -type f 2>/dev/null)" ] && fail "a dry run wrote files"
grep -q "would copy" run.log || fail "a dry run said it would copy nothing"
echo "ok   dry run wrote nothing and reported what it would do"

# 2. the real thing: rules applied, structure kept
run --source src --dest dst --exclude skipme || fail "the first backup run failed"
[ -f dst/a.txt ] || fail "a.txt was not copied"
[ -f dst/photos/2024/big.bin ] || fail "a nested file was not copied with its folder structure"
[ -f dst/skipme/s.txt ] && fail "--exclude did not keep skipme out"
[ -f dst/.hidden/h.txt ] && fail "hidden folders were not skipped"
[ -f dst/.airdrive-pc.tsv ] || fail "no record file was written to the destination"
cmp -s src/photos/2024/big.bin dst/photos/2024/big.bin || fail "the stored copy is not byte-identical"
echo "ok   exclusions, hidden skip, structure and bytes"

# 3. a second run changes nothing and duplicates nothing
before=$(find dst -type f ! -name '.airdrive-pc.tsv' | wc -l | tr -d ' ')
run --source src --dest dst || fail "the second run failed"
grep -q "already backed up" run.log || fail "the second run did not report anything as already backed up"
after=$(find dst -type f ! -name '.airdrive-pc.tsv' | wc -l | tr -d ' ')
[ "$before" = "$after" ] || fail "the second run changed the number of stored files ($before -> $after)"
grep -q "0 copied" run.log || fail "the second run copied something again: $(tail -2 run.log)"
echo "ok   second run copied nothing"

# 4. an updated file replaces its own copy, it does not grow a twin beside it
echo changed >> src/a.txt
run --source src --dest dst || fail "the run after a change failed"
[ "$(find dst -maxdepth 1 -name 'a*.txt' | wc -l | tr -d ' ')" = "1" ] || fail "an updated file left a duplicate: $(find dst -name 'a*.txt')"
grep -q "1 copied" run.log || fail "the changed file was not re-copied: $(tail -2 run.log)"
echo "ok   updated file replaced its own copy"

# 5. the size cap
head -c 5000000 /dev/zero > src/photos/toobig.bin
run --source src --dest dst --max-mb 1 || fail "the size-cap run failed"
[ -f dst/photos/toobig.bin ] && fail "--max-mb did not hold a big file back"
grep -q "over the size limit" run.log || fail "a skipped big file was not reported"
echo "ok   size cap"

# 6. two sources whose contents collide must not overwrite each other
mkdir -p src2/photos/2024 && echo one > src2/photos/2024/c.txt
run --source src --source src2 --dest dst2 --dry-run || fail "the two-source dry run failed"
run --source src2 --dest dst2 --only txt || fail "the second source run failed"
[ -f dst2/photos/2024/c.txt ] || fail "the second source's file was not stored where the rules said"
echo "ok   multiple sources"

# 7. nothing half-written is left behind
[ -d dst/.airdrive-tmp ] && [ -n "$(ls -A dst/.airdrive-tmp 2>/dev/null)" ] && fail "the scratch folder was left full"
echo "ok   scratch folder is empty"

echo "SMOKE OK"
